package com.nevoit.sfcompose.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import kotlin.jvm.functions.Function1
import org.gradle.api.*
import org.gradle.api.provider.*

abstract class SFComposeExtension {
    abstract Property<String> getCatalog()
    abstract SetProperty<String> getAdditionalCatalogs()
    abstract SetProperty<String> getRetain()
    abstract SetProperty<String> getIncludeVariants()
    abstract Property<Boolean> getAutomaticDependency()
}

class SFComposePlugin implements Plugin<Project> {
    @Override void apply(Project project) {
        def extension = project.extensions.create('sfcompose', SFComposeExtension)
        def metadata = new Properties()
        SFComposePlugin.getResourceAsStream('/sfcompose-plugin.properties').withCloseable { metadata.load(it) }
        extension.catalog.convention(metadata.getProperty('catalog'))
        extension.additionalCatalogs.convention([])
        extension.retain.convention([])
        extension.includeVariants.convention([])
        extension.automaticDependency.convention(true)
        project.pluginManager.withPlugin('com.android.application') {
            def dataDependencies = project.configurations.dependencyScope('sfcomposeCatalogData')
            def data = project.configurations.resolvable('sfcomposeBuildData') {
                transitive = false
                extendsFrom(dataDependencies.get())
            }
            def inventory = project.configurations.resolvable('sfcomposeInventory') { transitive = false }
            project.dependencies.addProvider('sfcomposeCatalogData', extension.catalog.map { coordinate ->
                coordinate.startsWith(':') ? project.dependencies.project(path: coordinate, configuration: 'sfcomposeBuildData') :
                    "${coordinate}:build-data@zip".toString()
            })
            project.afterEvaluate {
                if (extension.automaticDependency.get()) {
                    String coordinate = extension.catalog.get()
                    project.dependencies.add('implementation', coordinate.startsWith(':') ?
                        project.dependencies.project(path: coordinate) : coordinate)
                }
                extension.additionalCatalogs.get().each { coordinate ->
                    project.dependencies.add('implementation', coordinate.startsWith(':') ?
                        project.dependencies.project(path: coordinate) : coordinate)
                    project.dependencies.add('sfcomposeCatalogData', coordinate.startsWith(':') ?
                        project.dependencies.project(path: coordinate, configuration: 'sfcomposeBuildData') :
                        "${coordinate}:build-data@zip".toString())
                }
            }
            def android = project.extensions.getByType(ApplicationAndroidComponentsExtension)
            android.onVariants(android.selector().all(), { variant ->
                if (!extension.includeVariants.get().isEmpty() && !extension.includeVariants.get().contains(variant.name)) return
                String title = variant.name.capitalize()
                def assets = project.tasks.register("prepare${title}Sfcompose", BuildSymbolAssets) {
                    buildData.from(data)
                    retain.set(extension.retain)
                    workspaceDirectory.set(project.layout.projectDirectory)
                    outputDirectory.set(project.layout.buildDirectory.dir("generated/sfcompose/${variant.name}/assets"))
                    selectionReport.set(project.layout.buildDirectory.file("reports/sfcompose/${variant.name}-selection.json"))
                }
                variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(assets).toGet(ScopedArtifact.CLASSES.INSTANCE,
                    { task -> task.allJars } as Function1, { task -> task.allDirectories } as Function1)
                variant.sources.assets.addGeneratedSourceDirectory(assets, { task -> task.outputDirectory } as Function1)
                def report = project.tasks.register("report${title}Sfcompose", ReportSymbols) {
                    inventoryFiles.from(inventory)
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK.INSTANCE))
                    reportFile.set(project.layout.buildDirectory.file("reports/sfcompose/${variant.name}.json"))
                }
                project.tasks.matching { it.name == "assemble$title" }.configureEach { finalizedBy(report) }
            } as Action)
        }
    }
}
