package io.github.bohdaq.javadrift.gradle;
import org.gradle.api.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.*;
import java.util.List;
public final class JavadriftPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getPluginManager().apply("base");
        JavadriftExtension extension=project.getExtensions().create("javadrift",JavadriftExtension.class);
        extension.getWarnOnly().convention(false);extension.getFormat().convention("text");
        TaskProvider<JavadriftCheck> task=project.getTasks().register("javadriftCheck",JavadriftCheck.class,t->{
            t.setGroup("verification");t.setDescription("Check documentation against JVM APIs");
            t.getRoot().set(project.getLayout().getProjectDirectory());
            t.getDocuments().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*.md","**/*.adoc");tree.exclude("**/build/**","**/target/**","**/.git/**","**/.gradle/**");}));
            t.getSources().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*.java","**/*.kt","**/pom.xml","**/build.gradle","**/build.gradle.kts");tree.exclude("**/build/**","**/target/**","**/.git/**","**/.gradle/**");}));
            // Changes to config, baseline, Git refs and repository paths invalidate a full scan too.
            t.getConfiguration().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*");tree.exclude("**/build/**","**/target/**","**/.gradle/**","**/.git/objects/**","**/.git/logs/**");}));
            t.getSince().set(extension.getSince());t.getWarnOnly().set(extension.getWarnOnly());t.getFormat().set(extension.getFormat());
            t.getGitState().set(project.provider(()->io.github.bohdaq.javadrift.core.GitHistory.state(project.getProjectDir().toPath())));
            t.getProjectGroup().set(project.provider(()->project.getGroup().toString()));
            t.getProjectVersion().set(project.provider(()->project.getVersion().toString()));
            t.getProjectArtifact().set(project.getName());
            t.getReport().set(project.getLayout().getBuildDirectory().file("reports/javadrift/report.txt"));
        });
        project.getAllprojects().forEach(p->p.getPlugins().withType(JavaPlugin.class,plugin->{
            SourceSetContainer sources=p.getExtensions().getByType(SourceSetContainer.class);
            sources.named("main",main->task.configure(t->{
                t.getClassDirectories().from(main.getOutput().getClassesDirs());
                t.getDependencyClasspath().from(main.getCompileClasspath());
                t.dependsOn(p.getTasks().named(main.getClassesTaskName()));
            }));
        }));
        project.getTasks().named("check").configure(t->t.dependsOn(task));
    }
}
