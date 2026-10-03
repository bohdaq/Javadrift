package io.github.bohdaq.javadrift.gradle;
import org.gradle.api.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.*;
import java.util.Properties;
import org.gradle.api.attributes.Usage;
public final class JavadriftPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getPluginManager().apply("base");
        JavadriftExtension extension=project.getExtensions().create("javadrift",JavadriftExtension.class);
        extension.getWarnOnly().convention(false);extension.getFormat().convention("text");
        var runtime=project.getConfigurations().create("javadriftRuntime",c->{
            c.setCanBeConsumed(false);c.setCanBeResolved(true);
            c.setDescription("Isolated Javadrift analysis engine");
            c.getAttributes().attribute(Usage.USAGE_ATTRIBUTE,project.getObjects().named(Usage.class,Usage.JAVA_RUNTIME));
            c.defaultDependencies(deps->deps.add(project.getDependencies().create("io.github.bohdaq:javadrift-core:"+engineVersion())));
        });
        TaskProvider<JavadriftCheck> task=project.getTasks().register("javadriftCheck",JavadriftCheck.class,t->{
            t.setGroup("verification");t.setDescription("Check documentation against JVM APIs");
            t.getRoot().set(project.getLayout().getProjectDirectory());
            t.getEngineClasspath().from(runtime);
            t.getDocuments().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*.md","**/*.adoc");tree.exclude("**/build/**","**/target/**","**/.git/**","**/.gradle/**");}));
            t.getSources().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*.java","**/*.kt","**/pom.xml","**/build.gradle","**/build.gradle.kts");tree.exclude("**/build/**","**/target/**","**/.git/**","**/.gradle/**");}));
            // Changes to config, baseline, Git refs and repository paths invalidate a full scan too.
            t.getConfiguration().from(project.fileTree(project.getProjectDir(),tree->{tree.include("**/*");tree.exclude("**/build/**","**/target/**","**/.gradle/**","**/.git/objects/**","**/.git/logs/**");}));
            t.getSince().set(extension.getSince());t.getWarnOnly().set(extension.getWarnOnly());t.getFormat().set(extension.getFormat());
            t.getGitState().set(project.provider(()->GitInputs.state(project.getProjectDir().toPath())));
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
    private static String engineVersion() {
        try(var stream=JavadriftPlugin.class.getResourceAsStream("engine.properties")) {
            Properties properties=new Properties();properties.load(stream);return properties.getProperty("version");
        } catch(Exception e) {throw new GradleException("Cannot read Javadrift engine version",e);}
    }
}
