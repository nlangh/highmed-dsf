package org.highmed.dsf.bpe.plugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.highmed.dsf.bpe.ProcessPluginDefinition;
import org.highmed.dsf.bpe.process.ProcessKeyAndVersion;
import org.highmed.dsf.fhir.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;

import ca.uhn.fhir.context.FhirContext;

public class ProcessPluginProviderImpl implements ProcessPluginProvider, InitializingBean
{
	private static final class Pair<K, V>
	{
		final K k;
		final V v;

		Pair(K k, V v)
		{
			this.k = k;
			this.v = v;
		}
	}

	public static final String FILE_DRAFT_SUFIX = "-SNAPSHOT.jar";
	public static final String FOLDER_DRAFT_SUFIX = "-SNAPSHOT";

	private static final Logger logger = LoggerFactory.getLogger(ProcessPluginProviderImpl.class);

	private final FhirContext fhirContext;
	private final Path pluginDirectory;
	private final ApplicationContext mainApplicationContext;

	private List<ProcessPluginDefinitionAndClassLoader> definitions;

	public ProcessPluginProviderImpl(FhirContext fhirContext, Path pluginDirectory,
			ApplicationContext mainApplicationContext)
	{
		this.fhirContext = fhirContext;
		this.pluginDirectory = pluginDirectory;
		this.mainApplicationContext = mainApplicationContext;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(fhirContext, "fhirContext");
		Objects.requireNonNull(pluginDirectory, "pluginDirectory");
		Objects.requireNonNull(mainApplicationContext, "mainApplicationContext");
	}

	@Override
	public List<ProcessPluginDefinitionAndClassLoader> getDefinitions()
	{
		if (definitions == null)
		{
			synchronized (this)
			{
				if (definitions == null)
					definitions = handleDependencies(loadDefinitions());
			}
		}

		return definitions;
	}

	private List<ProcessPluginDefinitionAndClassLoader> loadDefinitions()
	{
		try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(pluginDirectory))
		{
			List<ProcessPluginDefinitionAndClassLoader> definitions = new ArrayList<>();

			directoryStream.forEach(p ->
			{
				if (Files.isReadable(p) && p.getFileName().toString().endsWith(".jar"))
				{
					ProcessPluginDefinitionAndClassLoader def = toJarDefinition(p);
					if (def != null)
						definitions.add(def);
				}
				else if (Files.isDirectory(p))
				{
					ProcessPluginDefinitionAndClassLoader def = toFolderDefinition(p);
					if (def != null)
						definitions.add(def);
				}
				else
					logger.warn("Ignoring file/folder {}", p.toAbsolutePath().toString());
			});

			return definitions;
		}
		catch (IOException e)
		{
			logger.warn("Error loading process plugin definitions", e);
			throw new RuntimeException(e);
		}
	}

	private ProcessPluginDefinitionAndClassLoader toJarDefinition(Path jarFile)
	{
		URLClassLoader classLoader = new URLClassLoader(jarFile.getFileName().toString(), new URL[] { toUrl(jarFile) },
				ClassLoader.getSystemClassLoader());

		return toDefinition(classLoader, null, Collections.singletonList(jarFile));
	}

	private ProcessPluginDefinitionAndClassLoader toFolderDefinition(Path folder)
	{
		List<Path> jars = getJars(folder);
		URLClassLoader classLoader = new URLClassLoader(folder.getFileName().toString(),
				jars.stream().map(this::toUrl).toArray(URL[]::new), ClassLoader.getSystemClassLoader());

		return toDefinition(classLoader, folder, jars);
	}

	private ProcessPluginDefinitionAndClassLoader toDefinition(ClassLoader classLoader, Path folder, List<Path> jars)
	{
		List<Provider<ProcessPluginDefinition>> definitions = ServiceLoader
				.load(ProcessPluginDefinition.class, classLoader).stream().collect(Collectors.toList());

		if (definitions.size() < 1)
		{
			logger.warn("Ignoring {} no {} found", jars.size() == 1 ? jars.toString() : folder.toString(),
					ProcessPluginDefinition.class.getName());
			return null;
		}
		else if (definitions.size() > 1)
		{
			logger.warn("Ignoring {} more than one {} found", folder.toString(),
					ProcessPluginDefinition.class.getName());
			return null;
		}

		boolean draft = jars.size() == 1 ? jars.get(0).getFileName().toString().endsWith(FILE_DRAFT_SUFIX)
				: folder.toString().endsWith(FOLDER_DRAFT_SUFIX);

		return new ProcessPluginDefinitionAndClassLoader(fhirContext, jars, definitions.get(0).get(), classLoader,
				draft);
	}

	private List<Path> getJars(Path folder)
	{
		try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(folder))
		{
			List<Path> jarFiles = new ArrayList<>();

			directoryStream.forEach(p ->
			{
				if (Files.isReadable(p) && p.getFileName().toString().endsWith(".jar"))
					jarFiles.add(p);
				else
					logger.warn("Ignoring folder/file {}", p.toAbsolutePath().toString());
			});

			return jarFiles;
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private URL toUrl(Path p)
	{
		try
		{
			return p.toUri().toURL();
		}
		catch (MalformedURLException e)
		{
			throw new RuntimeException(e);
		}
	}

	private List<ProcessPluginDefinitionAndClassLoader> handleDependencies(
			List<ProcessPluginDefinitionAndClassLoader> definitions)
	{
		Map<String, ProcessPluginDefinitionAndClassLoader> definitionsByJar = new HashMap<>();
		definitions.stream()
				.forEach(def -> def.getJars().forEach(p -> definitionsByJar.put(p.getFileName().toString(), def)));

		return definitions.stream().map(def -> withDependencies(def, definitionsByJar)).collect(Collectors.toList());
	}

	private ProcessPluginDefinitionAndClassLoader withDependencies(ProcessPluginDefinitionAndClassLoader definition,
			Map<String, ProcessPluginDefinitionAndClassLoader> definitionsByJar)
	{
		if (definition.getDefinition().getDependencyNamesAndVersions().isEmpty())
			return definition;
		else
		{
			if (definition.getClassLoader() instanceof URLClassLoader)
			{
				try
				{
					((URLClassLoader) definition.getClassLoader()).close();
				}
				catch (IOException e)
				{
					logger.warn("Error while closing not needed, initial url class loader {}",
							definition.getClassLoader().getName());
				}
			}

			String definitionClassName = definition.getDefinition().getClass().getName();
			List<Path> dependencyJars = definition.getDefinition().getDependencyNamesAndVersions().stream()
					.flatMap(dependency -> findDefinition(definitionsByJar, dependency)).collect(Collectors.toList());

			if (definition.getJars().size() == 1)
				return toJarDefinitionWithDependencies(definitionClassName, definition.getJars().get(0),
						dependencyJars);
			else
				return toFolderDefinitionWithDependencies(definitionClassName, definition.getJars().get(0).getParent(),
						dependencyJars);
		}
	}

	private Stream<Path> findDefinition(Map<String, ProcessPluginDefinitionAndClassLoader> definitionsByJar,
			String dependency)
	{
		ProcessPluginDefinitionAndClassLoader byJar = definitionsByJar.get(dependency + ".jar");
		if (byJar != null)
			return byJar.getJars().stream();

		ProcessPluginDefinitionAndClassLoader bySnapshotJar = definitionsByJar.get(dependency + "-SNAPSHOT.jar");
		if (bySnapshotJar != null)
			return bySnapshotJar.getJars().stream();

		throw new RuntimeException("Dependency " + dependency + " not found");
	}

	private ProcessPluginDefinitionAndClassLoader toJarDefinitionWithDependencies(String definitionClassName,
			Path jarFile, List<Path> dependencyJars)
	{
		URLClassLoader classLoader = new URLClassLoader(jarFile.getFileName().toString(),
				Stream.concat(Stream.of(jarFile), dependencyJars.stream()).map(this::toUrl).toArray(URL[]::new),
				ClassLoader.getSystemClassLoader());

		return toDefinitionWithDependency(definitionClassName, classLoader, null, Collections.singletonList(jarFile));
	}

	private ProcessPluginDefinitionAndClassLoader toFolderDefinitionWithDependencies(String definitionClassName,
			Path folder, List<Path> dependencyJars)
	{
		List<Path> jars = getJars(folder);
		URLClassLoader classLoader = new URLClassLoader(folder.getFileName().toString(),
				Stream.concat(jars.stream(), dependencyJars.stream()).map(this::toUrl).toArray(URL[]::new),
				ClassLoader.getSystemClassLoader());

		return toDefinitionWithDependency(definitionClassName, classLoader, folder, jars);
	}

	private ProcessPluginDefinitionAndClassLoader toDefinitionWithDependency(String definitionClassName,
			ClassLoader classLoader, Path folder, List<Path> jars)
	{
		List<Provider<ProcessPluginDefinition>> definitions = ServiceLoader
				.load(ProcessPluginDefinition.class, classLoader).stream().collect(Collectors.toList());

		ProcessPluginDefinition definition = definitions.stream().map(provider -> provider.get())
				.filter(def -> def.getClass().getName().equals(definitionClassName)).findFirst().orElseThrow(
						() -> new RuntimeException("ProcessPluginDefinition " + definitionClassName + " not found"));

		boolean draft = jars.size() == 1 ? jars.get(0).getFileName().toString().endsWith(FILE_DRAFT_SUFIX)
				: folder.toString().endsWith(FOLDER_DRAFT_SUFIX);

		return new ProcessPluginDefinitionAndClassLoader(fhirContext, jars, definition, classLoader, draft);
	}

	@Override
	public Map<ProcessKeyAndVersion, ClassLoader> getClassLoadersByProcessDefinitionKeyAndVersion()
	{
		return getDefinitions().stream()
				.flatMap(def -> def.getProcessKeysAndVersions().stream()
						.map(keyAndVersion -> new Pair<>(keyAndVersion, def.getClassLoader())))
				.collect(Collectors.toMap(p -> p.k, p -> p.v, dupplicatedProcessKeyVersion()));
	}

	private <T> BinaryOperator<T> dupplicatedProcessKeyVersion()
	{
		return (v1, v2) ->
		{
			throw new RuntimeException("duplicate processes, check process keys/versions");
		};
	}

	@Override
	public Map<ProcessKeyAndVersion, ApplicationContext> getApplicationContextsByProcessDefinitionKeyAndVersion()
	{
		return getDefinitions().stream().flatMap(def -> def.getProcessKeysAndVersions().stream().map(
				keyAndVersion -> new Pair<>(keyAndVersion, def.createPluginApplicationContext(mainApplicationContext))))
				.collect(Collectors.toMap(p -> p.k, p -> p.v, dupplicatedProcessKeyVersion()));
	}

	@Override
	public Map<ProcessKeyAndVersion, ProcessPluginDefinitionAndClassLoader> getDefinitionByProcessKeyAndVersion()
	{
		return getDefinitions().stream().flatMap(
				def -> def.getProcessKeysAndVersions().stream().map(keyAndVersion -> new Pair<>(keyAndVersion, def)))
				.collect(Collectors.toMap(p -> p.k, p -> p.v, dupplicatedProcessKeyVersion()));
	}

	@Override
	public Map<String, ResourceProvider> getResouceProvidersByDpendencyNameAndVersion()
	{
		return getDefinitions().stream().collect(
				Collectors.toMap(def -> def.getDefinition().getNameAndVersion(), def -> def.getResourceProvider()));
	}

	@Override
	public List<ProcessKeyAndVersion> getProcessKeyAndVersions()
	{
		return getDefinitions().stream().flatMap(def -> def.getProcessKeysAndVersions().stream())
				.collect(Collectors.toList());
	}

	@Override
	public List<ProcessKeyAndVersion> getDraftProcessKeyAndVersions()
	{
		return getDefinitions().stream().filter(ProcessPluginDefinitionAndClassLoader::isDraft)
				.flatMap(def -> def.getProcessKeysAndVersions().stream()).collect(Collectors.toList());
	}
}
