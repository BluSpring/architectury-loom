/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.forge.fg2;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import net.md_5.specialsource.AccessChange;
import net.md_5.specialsource.AccessMap;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftLegacyPatchedProvider extends MinecraftPatchedProvider {
	// Step 1: Binary Patch (global)
	private File minecraftClientPatchedJar;
	private File minecraftServerPatchedJar;
	// Step 2: Merge (global)
	private File minecraftMergedPatchedJar;
	// Step 4: Access Transform (global or project)
	private File minecraftMergedPatchedAtJar;

	private File forgeJar;

	public MinecraftLegacyPatchedProvider(Project project) {
		super(project);
	}

	@Override
	public void initFiles() throws IOException {
		filesDirty = false;
		initAts();

		File globalCache = getExtension().getForgeProvider().getGlobalCache();
		File projectDir = usesProjectCache() ? getExtension().getForgeProvider().getProjectCache() : globalCache;
		projectDir.mkdirs();

		minecraftClientPatchedJar = new File(globalCache, "client-patched.jar");
		minecraftServerPatchedJar = new File(globalCache, "server-patched.jar");
		minecraftMergedPatchedJar = new File(globalCache, "merged-patched.jar");
		minecraftMergedPatchedAtJar = new File(projectDir, "merged-at-patched.jar");

		forgeJar = new File(globalCache, "forge.jar");

		if (isRefreshDeps() || Stream.of(getGlobalCaches()).anyMatch(((Predicate<File>) File::exists).negate())
				|| !isPatchedJarUpToDate(forgeJar) || !isPatchedJarUpToDate(minecraftMergedPatchedAtJar)) {
			cleanAllCache();
		} else if (atDirty || Stream.of(getProjectCache()).anyMatch(((Predicate<File>) File::exists).negate())) {
			cleanProjectCache();
		}
	}

	@Override
	protected File[] getGlobalCaches() {
		return new File[] {
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftMergedPatchedJar,
				forgeJar,
		};
	}

	@Override
	protected File[] getProjectCache() {
		return new File[] {
				minecraftMergedPatchedAtJar,
		};
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		initFiles();

		if (atDirty) {
			getProject().getLogger().lifecycle(":found dirty access transformers");
		}
	}

	@Override
	public void finishProvide() throws Exception {
		if (!forgeJar.exists()) {
			filesDirty = true;
			patchForge(getProject().getLogger());
			applyLoomPatchVersion(forgeJar.toPath());
		}

		if (!minecraftClientPatchedJar.exists() || !minecraftServerPatchedJar.exists()) {
			filesDirty = true;
			patchJars(getProject().getLogger());
		}

		if (filesDirty || !minecraftMergedPatchedJar.exists()) {
			filesDirty = true;
			mergeJars(getProject().getLogger());
		}

		if (atDirty || filesDirty || !minecraftMergedPatchedAtJar.exists()) {
			filesDirty = true;
			accessTransformForge(getProject().getLogger());
			applyLoomPatchVersion(minecraftMergedPatchedAtJar.toPath());
		}
	}

	private void patchForge(Logger logger) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching forge");

		Files.copy(getExtension().getForgeUniversalProvider().getForge().toPath(), forgeJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

		// For the development environment, we need to remove the binpatches, otherwise forge will try to re-apply them
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(forgeJar, false)) {
			Files.delete(fs.get().getPath("binpatches.pack.lzma"));
		}

		// Older versions of Forge rely on utility classes from log4j-core 2.0-beta9 but we'll upgrade the runtime to a
		// release version (so we can use the TerminalConsoleAppender) where some of those classes have been moved from
		// a `helpers` to a `utils` package.
		// To allow Forge to work regardless, we'll re-package those helper classes into the forge jar.
		Path log4jBeta9 = Arrays.stream(TinyRemapperHelper.getMinecraftDependencies(getProject()))
				.filter(it -> it.getFileName().toString().equals("log4j-core-2.0-beta9.jar"))
				.findAny()
				.orElse(null);
		if (log4jBeta9 != null) {
			Predicate<Path> isHelper = path -> path.startsWith("/org/apache/logging/log4j/core/helpers");
			walkFileSystems(log4jBeta9.toFile(), forgeJar, isHelper, this::copyReplacing);
		}

		logger.lifecycle(":patched forge in " + stopwatch.stop());
	}

	private void patchJars(Logger logger) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jars");

		MinecraftProviderImpl minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftProvider.minecraftServerJar, minecraftServerPatchedJar, patchProvider.serverPatches);
		patchJars(minecraftProvider.minecraftClientJar, minecraftClientPatchedJar, patchProvider.clientPatches);

		logger.lifecycle(":patched jars in " + stopwatch.stop());
	}

	@Override
	protected void patchJars(File clean, File output, Path patches) throws Exception {
		super.patchJars(clean, output, patches);

		// Patching only preserves affected classes, everything else we need to copy manually
		copyMissingClasses(clean, output);
		copyNonClassFiles(clean, output);

		// Workaround Forge patches apparently violating the JVM spec (see ParameterAnnotationsFixer for details)
		modifyClasses(output, ParameterAnnotationsFixer::new);
	}

	private void mergeJars(Logger logger) throws Exception {
		logger.info(":merging jars");
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (JarMerger jarMerger = new JarMerger(minecraftClientPatchedJar, minecraftServerPatchedJar, minecraftMergedPatchedJar)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		// The JarMerger adds Sided annotations but so do the Forge patches. The latter doesn't require extra
		// dependencies beyond Forge, so we'll keep those and remove the Fabric ones.
		modifyClasses(minecraftMergedPatchedJar, FabricSideStripper::new);

		logger.info(":merged jars in " + stopwatch);
	}

	private void accessTransformForge(Logger logger) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();

		logger.lifecycle(":access transforming minecraft");

		MappingTree mappingTree = getExtension().getMappingsProvider().getMappingsWithSrg();

		AccessMap accessMap = new LegacyAccessMap();

		byte[] forgeAt = ZipUtils.unpack(forgeJar.toPath(), "forge_at.cfg");
		accessMap.loadAccessTransformer(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(forgeAt))));

		for (File projectAt : projectAts) {
			accessMap.loadAccessTransformer(projectAt);
		}

		Files.copy(minecraftMergedPatchedJar.toPath(), minecraftMergedPatchedAtJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

		modifyClasses(minecraftMergedPatchedAtJar, writer -> new AccessTransformingVisitor(accessMap, mappingTree, writer));

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	private void modifyClasses(File jarFile, Function<ClassVisitor, ClassVisitor> func) throws Exception {
		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toURI()), ImmutableMap.of("create", false))) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] original = Files.readAllBytes(file);

					ClassReader reader = new ClassReader(original);
					ClassWriter writer = new ClassWriter(reader, 0);
					reader.accept(func.apply(writer), 0);

					byte[] modified = writer.toByteArray();

					if (!Arrays.equals(original, modified)) {
						Files.write(file, modified, StandardOpenOption.TRUNCATE_EXISTING);
					}
				});
			}

			completer.complete();
		}
	}

	public File getMergedJar() {
		return minecraftMergedPatchedAtJar;
	}

	public File getForgeMergedJar() {
		return forgeJar;
	}

	/**
	 * It seems that Forge patches produce class files which are in violation of the JVM spec. Specifically, the value
	 * of Runtime[In]VisibleParameterAnnotation.num_parameters is by SE8 spec required to match the number of formal
	 * parameters of the method (and may be ignored in favor of directly looking at the method arguments, which is
	 * indeed what the OpenJDK 8 compiler does). Using a smaller value (possible if e.g. the last parameter has no
	 * annotations) will cause the compiler to read past the end of the table, throwing an exception and therefore being
	 * unable to read the class file.
	 * <br>
	 * This class visitor fixes that by ignoring the original num_parameters value, letting the MethodVisitor compute a
	 * new value based on its signature. This will at first produce an invalid count when there are synthetic parameters
	 * but later, during mergeJars, those will be properly offset (enableSyntheticParamsOffset).
	 * <br>
	 * SE8 JVM spec: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.18
	 * Example method affected: RenderGlobal.ContainerLocalRenderInformation(RenderChunk, EnumFacing, int)
	 */
	private static class ParameterAnnotationsFixer extends ClassVisitor {
		private ParameterAnnotationsFixer(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);

			// This issue has so far only been observed with constructors, so we can skip everything else
			if (name.equals("<init>")) {
				methodVisitor = new MethodFixer(methodVisitor);
			}

			return methodVisitor;
		}

		private static class MethodFixer extends MethodVisitor {
			private MethodFixer(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
				// Not calling visitAnnotableParameterCount will cause it to compute its value from the method signature
			}
		}
	}

	private static class FabricSideStripper extends ClassVisitor {
		private static final String SIDED_DESCRIPTOR = "Lnet/fabricmc/api/Environment;";

		private FabricSideStripper(ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (descriptor.equals(SIDED_DESCRIPTOR)) {
				return null;
			}

			return super.visitAnnotation(descriptor, visible);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			return new FieldStripper(super.visitField(access, name, descriptor, signature, value));
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			return new MethodStripper(super.visitMethod(access, name, descriptor, signature, exceptions));
		}

		private static class FieldStripper extends FieldVisitor {
			private FieldStripper(FieldVisitor fieldVisitor) {
				super(Opcodes.ASM9, fieldVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (descriptor.equals(SIDED_DESCRIPTOR)) {
					return null;
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}

		private static class MethodStripper extends MethodVisitor {
			private MethodStripper(MethodVisitor methodVisitor) {
				super(Opcodes.ASM9, methodVisitor);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (descriptor.equals(SIDED_DESCRIPTOR)) {
					return null;
				}

				return super.visitAnnotation(descriptor, visible);
			}
		}
	}

	private static class LegacyAccessMap extends AccessMap {
		@Override
		public void addAccessChange(String key, AccessChange accessChange) {
			// Forge's AT separates fields/methods from their owner by a space but we require a slash
			int spaceIdx = key.indexOf(' ');

			if (spaceIdx != -1 && key.charAt(spaceIdx + 1) != '(') {
				key = key.replaceFirst(" ", "/");
			}

			super.addAccessChange(key, accessChange);
		}
	}

	private static class AccessTransformingVisitor extends ClassVisitor {
		private final AccessMap accessMap;
		private final MappingTree mappingTree;
		private final int src;
		private final int dst;

		private MappingTree.ClassMapping classMapping;
		private String mappedClassName;

		private AccessTransformingVisitor(AccessMap accessMap, MappingTree mappingTree, ClassVisitor classVisitor) {
			super(Opcodes.ASM9, classVisitor);
			this.accessMap = accessMap;
			this.mappingTree = mappingTree;
			this.src = mappingTree.getNamespaceId("official");
			this.dst = mappingTree.getNamespaceId("srg");
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			classMapping = mappingTree.getClass(name, src);
			mappedClassName = classMapping != null ? classMapping.getName(dst) : name;
			access = accessMap.applyClassAccess(mappedClassName, access);
			super.visit(version, access, name, signature, superName, interfaces);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			MappingTree.FieldMapping field = classMapping != null ? classMapping.getField(name, descriptor, src) : null;
			String mappedName = field != null ? field.getName(dst) : name;
			access = accessMap.applyFieldAccess(mappedClassName, mappedName, access);
			return super.visitField(access, name, descriptor, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			MappingTree.MethodMapping method = classMapping != null ? classMapping.getMethod(name, desc, src) : null;
			String mappedName = method != null ? method.getName(dst) : name;
			String mappedDesc = method != null ? method.getDesc(dst) : mappingTree.mapDesc(desc, src, dst);
			access = accessMap.applyMethodAccess(mappedClassName, mappedName, mappedDesc, access);
			return super.visitMethod(access, name, desc, signature, exceptions);
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			MappingTree.ClassMapping classMapping = mappingTree.getClass(name, src);
			access = accessMap.applyClassAccess(classMapping != null ? classMapping.getName(dst) : name, access);
			super.visitInnerClass(name, outerName, innerName, access);
		}
	}
}
