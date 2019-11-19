/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.workers.internal

import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.ToBeFixedForInstantExecution
import org.gradle.integtests.fixtures.timeout.IntegrationTestTimeout
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import org.gradle.workers.IsolationMode
import org.gradle.workers.fixtures.WorkerExecutorFixture
import org.junit.Rule
import spock.lang.Issue
import spock.lang.Unroll

import static org.gradle.workers.fixtures.WorkerExecutorFixture.ISOLATION_MODES
import static org.gradle.workers.fixtures.WorkerExecutorFixture.WORKER_METHODS

@IntegrationTestTimeout(120)
@Unroll
class WorkerExecutorIntegrationTest extends AbstractWorkerExecutorIntegrationTest {

    @Rule
    public final BlockingHttpServer blockingServer = new BlockingHttpServer()

    def buildOperations = new BuildOperationsFixture(executer, temporaryFolder)

    @ToBeFixedForInstantExecution
    def "can create and use a work action defined in buildSrc in #isolationMode"() {
        fixture.withWorkActionClassInBuildSrc()

        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
            }
        """

        when:
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        when:
        file('buildSrc/src/main/java/AnotherFoo.java') << """
            class AnotherFoo extends org.gradle.other.Foo {
            }
        """
        buildFile << """
            runInWorker {
                foo = new AnotherFoo()
            }
        """
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        where:
        isolationMode << ISOLATION_MODES
    }

    @ToBeFixedForInstantExecution
    def "can create and use a work action defined in build script in #isolationMode"() {
        fixture.withWorkActionClassInBuildScript()

        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
            }
        """

        when:
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        when:
        file('buildSrc/src/main/java/AnotherFoo.java') << """
            class AnotherFoo extends org.gradle.other.Foo {
            }
        """
        buildFile << """
            runInWorker {
                foo = new AnotherFoo()
            }
        """
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        where:
        isolationMode << ISOLATION_MODES
    }

    @ToBeFixedForInstantExecution
    def "can create and use a work action defined in an external jar in #isolationMode"() {
        def workActionJarName = "workAction.jar"
        withWorkActionClassInExternalJar(file(workActionJarName))

        buildFile << """
            buildscript {
                dependencies {
                    classpath files("$workActionJarName")
                }
            }

            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
            }
        """

        when:
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        when:
        buildFile << """
            runInWorker {
                foo = new AnotherFoo()
            }
        """
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        where:
        isolationMode << ISOLATION_MODES
    }

    def "re-uses an existing idle worker daemon"() {
        executer.withWorkerDaemonsExpirationDisabled()
        fixture.withWorkActionClassInBuildSrc()

        buildFile << """
            task runInDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }

            task reuseDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
                dependsOn runInDaemon
            }
        """

        when:
        succeeds("reuseDaemon")

        then:
        assertSameDaemonWasUsed("runInDaemon", "reuseDaemon")
    }

    def "starts a new worker daemon when existing worker daemons are incompatible"() {
        fixture.withWorkActionClassInBuildSrc()

        buildFile << """
            task runInDaemon(type: WorkerTask)

            task startNewDaemon(type: WorkerTask) {
                dependsOn runInDaemon
                isolationMode = IsolationMode.PROCESS

                // Force a new daemon to be used
                additionalForkOptions = {
                    it.systemProperty("foo", "bar")
                }
            }
        """

        when:
        succeeds("startNewDaemon")

        then:
        assertDifferentDaemonsWereUsed("runInDaemon", "startNewDaemon")
    }

    @Issue("https://github.com/gradle/gradle/issues/10411")
    def "does not leak project state across multiple builds"() {
        fixture.withWorkActionClassInBuildSrc()
        executer.withBuildJvmOpts('-Xms256m', '-Xmx512m').requireIsolatedDaemons().requireDaemon()

        buildFile << """
            ext.memoryHog = new byte[1024*1024*150] // ~150MB
            
            tasks.withType(WorkerTask) { task ->
                isolationMode = IsolationMode.PROCESS
                // Force a new daemon to be used
                additionalForkOptions = {
                    it.systemProperty("foobar", task.name)
                }
            }
            task startDaemon1(type: WorkerTask)
            task startDaemon2(type: WorkerTask)
            task startDaemon3(type: WorkerTask)
        """

        when:
        succeeds("startDaemon1")
        succeeds("startDaemon2")
        succeeds("startDaemon3")

        then:
        assertDifferentDaemonsWereUsed("startDaemon1", "startDaemon2")
        assertDifferentDaemonsWereUsed("startDaemon2", "startDaemon3")
        assertDifferentDaemonsWereUsed("startDaemon1", "startDaemon3")
    }

    def "starts a new worker daemon when there are no idle compatible worker daemons available"() {
        blockingServer.start()
        blockingServer.expectConcurrent("runInDaemon", "startNewDaemon")

        fixture.withWorkActionClassInBuildSrc()
        fixture.withBlockingWorkActionClassInBuildSrc("http://localhost:${blockingServer.port}")

        buildFile << """
            task runInDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
                workActionClass = BlockingWorkAction.class
            }

            task startNewDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
                workActionClass = BlockingWorkAction.class
            }

            task runAllDaemons {
                dependsOn runInDaemon, startNewDaemon
            }
        """

        when:
        args("--parallel")
        succeeds("runAllDaemons")

        then:
        assertDifferentDaemonsWereUsed("runInDaemon", "startNewDaemon")
    }

    def "re-uses an existing compatible worker daemon when a different work action is executed"() {
        executer.withWorkerDaemonsExpirationDisabled()
        fixture.withAlternateWorkActionClassInBuildSrc()

        buildFile << """
            task runInDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
            }

            task reuseDaemon(type: WorkerTask) {
                isolationMode = IsolationMode.PROCESS
                workActionClass = AlternateWorkAction.class
                dependsOn runInDaemon
            }
        """

        when:
        succeeds("reuseDaemon")

        then:
        assertSameDaemonWasUsed("runInDaemon", "reuseDaemon")
    }

    def "throws if worker used from a thread with no current build operation in #isolationMode"() {
        given:
        fixture.withWorkActionClassInBuildSrc()

        and:
        buildFile << """
            class WorkerTaskUsingCustomThreads extends WorkerTask {
                @TaskAction
                void executeTask() {
                    def thrown = null
                    def customThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                workerExecutor."${workerMethod}"({ config ->
                                    if (config instanceof ProcessWorkerSpec) {
                                        forkOptions.maxHeapSize = "64m"
                                        forkOptions(additionalForkOptions)
                                    }
                                    if (config instanceof ClassLoaderWorkerSpec) {
                                        classpath.from(additionalClasspath)
                                    }
                                }).submit(workActionClass) {
                                    files = list.collect { it as String }
                                    outputDir = new File(outputFileDirPath)
                                    foo = owner.foo
                                }
                            } catch(Exception ex) {
                                thrown = ex
                            }
                        }
                    })
                    customThread.start()
                    customThread.join()
                    if(thrown) {
                        throw thrown
                    }
                }
            }

            task runInWorker(type: WorkerTaskUsingCustomThreads)
        """.stripIndent()

        when:
        fails 'runInWorker'

        then:
        failure.assertHasCause 'An attempt was made to submit work from a thread not managed by Gradle.  Work may only be submitted from a Gradle-managed thread.'

        where:
        workerMethod << WORKER_METHODS.values().toUnique()
    }

    def "uses an inferred display name for work items in #isolationMode"() {
        given:
        fixture.withWorkActionClassInBuildSrc()
        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
            }
        """

        when:
        succeeds("runInWorker")

        then:
        def operation = buildOperations.only(ExecuteWorkItemBuildOperationType)
        operation.displayName == "org.gradle.test.TestWorkAction"
        with (operation.details) {
            className == "org.gradle.test.TestWorkAction"
            displayName == "org.gradle.test.TestWorkAction"
        }

        where:
        isolationMode << ISOLATION_MODES
    }

    def "includes failures in build operation in #isolationMode"() {
        given:
        fixture.withWorkActionClassInBuildSrc()
        fixture.workActionThatFails.writeToBuildFile()
        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
                workActionClass = WorkActionThatFails.class
            }
        """

        when:
        fails("runInWorker")

        then:
        def operation = buildOperations.only(ExecuteWorkItemBuildOperationType)
        operation.displayName == "WorkActionThatFails"
        operation.failure == "java.lang.RuntimeException: Failure from work action"

        where:
        isolationMode << ISOLATION_MODES
    }

    def "can use a parameter that references classes in other packages in #isolationMode"() {
        fixture.withWorkActionClassInBuildSrc()
        withParameterClassReferencingClassInAnotherPackage()

        buildFile << """
            task runInWorker(type: WorkerTask) {
                isolationMode = $isolationMode
            }
        """

        expect:
        succeeds("runInWorker")

        where:
        isolationMode << ISOLATION_MODES
    }

    def "classloader is not isolated when using IsolationMode.NONE"() {
        fixture.withWorkActionClassInBuildScript()

        buildFile << """
            class MutableItem {
                static String value = "foo"
            }
            
            abstract class MutatingWorkAction extends TestWorkAction {
                @Inject
                public MutatingWorkAction() { }
                
                public void execute() {
                    MutableItem.value = getParameters().files[0]
                }
            }
            
            task mutateValue(type: WorkerTask) {
                list = [ "bar" ]
                isolationMode = IsolationMode.NONE
                workActionClass = MutatingWorkAction.class
            } 
            
            task verifyNotIsolated {
                dependsOn mutateValue
                doLast {
                    assert MutableItem.value == "bar"
                }
            }
        """

        expect:
        succeeds "verifyNotIsolated"
    }

    def "user classes are isolated when using IsolationMode.CLASSLOADER"() {
        fixture.withWorkActionClassInBuildScript()

        buildFile << """
            class MutableItem {
                static String value = "foo"
            }
            
            abstract class MutatingWorkAction extends TestWorkAction {
                @Inject
                public MutatingWorkAction() { }
                
                public void execute() {
                    MutableItem.value = getParameters().files[0]
                }
            }
            
            task mutateValue(type: WorkerTask) {
                list = [ "bar" ]
                isolationMode = IsolationMode.CLASSLOADER
                workActionClass = MutatingWorkAction.class
            } 
            
            task verifyIsolated {
                dependsOn mutateValue
                doLast {
                    assert MutableItem.value == "foo"
                }
            }
        """

        expect:
        succeeds "verifyIsolated"
    }

    def "user classpath is isolated when using #isolationMode"() {
        fixture.withWorkActionClassInBuildScript()

        buildFile << """
            import java.util.jar.Manifest 
            
            repositories {
                mavenCentral()
            }
            
            configurations {
                customGuava
            }
            
            dependencies {
                customGuava "com.google.guava:guava:23.1-jre"
            }
            
            abstract class GuavaVersionWorkAction extends TestWorkAction {
                @Inject
                public GuavaVersionWorkAction() { }
                
                public void execute() {
                    Enumeration<URL> resources = this.getClass().getClassLoader()
                            .getResources("META-INF/MANIFEST.MF")
                    while (resources.hasMoreElements()) {
                        InputStream inputStream = resources.nextElement().openStream()
                        Manifest manifest = new Manifest(inputStream)
                        java.util.jar.Attributes mainAttributes = manifest.getMainAttributes()
                        String symbolicName = mainAttributes.getValue("Bundle-SymbolicName")
                        if ("com.google.guava".equals(symbolicName)) {
                            println "Guava version: " + mainAttributes.getValue("Bundle-Version")
                            break
                        }
                    }
                    
                    // This method was removed in Guava 24.0
                    def predicatesClass = this.getClass().getClassLoader().loadClass("com.google.common.base.Predicates")
                    assert predicatesClass.getDeclaredMethods().any { it.name == "assignableFrom" }
                }
            }
            
            task checkGuavaVersion(type: WorkerTask) {
                isolationMode = IsolationMode.${isolationMode}
                workActionClass = GuavaVersionWorkAction.class
                additionalClasspath = configurations.customGuava
            } 
        """

        expect:
        succeeds "checkGuavaVersion"

        and:
        outputContains("Guava version: 23.1.0.jre")

        where:
        isolationMode << [IsolationMode.CLASSLOADER, IsolationMode.PROCESS]
    }

    def "classloader is minimal when using #isolationMode"() {
        fixture.withWorkActionClassInBuildSrc()

        buildFile << """         
            abstract class SneakyWorkAction extends TestWorkAction {            
                @Inject
                public SneakyWorkAction() { }
                
                public void execute() {
                    super.execute()
                    // These classes were chosen to be relatively stable and would be unusual to see in a worker. 
                    def gradleApiClasses = [
                        "${com.google.common.collect.Lists.canonicalName}",
                    ]
                    def reachableClasses = gradleApiClasses.findAll { reachable(it) }
                    if (!reachableClasses.empty) {
                        throw new IllegalArgumentException("These classes should not be visible to the worker action: " + reachableClasses)
                    }
                }
                
                boolean reachable(String classname) {
                    try {
                        Class.forName(classname)
                        // bad! the class was leaked into the worker classpath
                        return true
                    } catch (Exception e) {
                        // The class was not found in the classpath
                        return false
                    }
                }
            }
            
            task runInWorker(type: WorkerTask) {
                isolationMode = IsolationMode.$isolationMode
                workActionClass = SneakyWorkAction
            } 
        """

        when:
        succeeds("runInWorker", "-i")
        then:
        assertWorkerExecuted("runInWorker")

        where:
        isolationMode << [IsolationMode.CLASSLOADER, IsolationMode.PROCESS]
    }

    @Requires(TestPrecondition.NOT_WINDOWS)
    @Issue("https://github.com/gradle/gradle/issues/8628")
    def "can find resources in the classpath via the context classloader using #isolationMode"() {
        fixture.withWorkActionClassInBuildSrc()

        file('foo.txt').text = "foo!"
        buildFile << """
            apply plugin: "base"

            abstract class ResourceWorkAction extends TestWorkAction {
                @Inject
                public ResourceWorkAction() { }

                public void execute() {
                    super.execute()
                    def resource = Thread.currentThread().getContextClassLoader().getResource("foo.txt")
                    assert resource != null && resource.getPath().endsWith('build/libs/foo.jar!/foo.txt')
                }
            }

            task jarFoo(type: Jar) {
                archiveBaseName = 'foo'
                from 'foo.txt'
            }

            task runInWorker(type: WorkerTask) {
                isolationMode = IsolationMode.${isolationMode}
                workActionClass = ResourceWorkAction
                additionalClasspath = tasks.jarFoo.outputs.files
                dependsOn jarFoo
            } 
        """

        when:
        succeeds("runInWorker")

        then:
        assertWorkerExecuted("runInWorker")

        where:
        isolationMode << [IsolationMode.CLASSLOADER, IsolationMode.PROCESS]
    }

    def "workers that change the context classloader don't affect future work in #isolationMode"() {
        fixture.withWorkActionClassInBuildScript()

        WorkerExecutorFixture.WorkActionClass workerThatChangesContextClassLoader = fixture.getWorkActionThatCreatesFiles("ClassLoaderChangingWorker")
        workerThatChangesContextClassLoader.with {
            action += """
                URL[] urls = parameters.files.collect { new File(getParameters().getOutputDir(), it).toURI().toURL() }
                ClassLoader classloader = new URLClassLoader(urls)
                Thread.currentThread().setContextClassLoader(classloader)
            """
        }
        workerThatChangesContextClassLoader.writeToBuildFile()

        WorkerExecutorFixture.WorkActionClass workerThatChecksClassLoader = fixture.getWorkActionThatCreatesFiles("ClassLoaderVerifyingWorker")
        workerThatChecksClassLoader.with {
            action += """
                File outputDir = new File(getParameters().getOutputDir().absolutePath.replace("checkClassLoader", "changeClassloader"))
                URL[] urls = parameters.files.collect { new File(outputDir, it).toURI().toURL() }
                assert !urls.any { Thread.currentThread().getContextClassLoader().URLs.contains(it) }
            """
        }
        workerThatChecksClassLoader.writeToBuildFile()

        buildFile << """
            task changeClassloader(type: WorkerTask) {
                isolationMode = $isolationMode
                workActionClass = ${workerThatChangesContextClassLoader.name}.class
            }
            
            task checkClassLoader(type: WorkerTask) {
                dependsOn changeClassloader
                isolationMode = $isolationMode
                workActionClass = ${workerThatChecksClassLoader.name}.class
            }
        """

        expect:
        succeeds "checkClassLoader"

        and:
        assertWorkerExecuted("changeClassloader")
        assertWorkerExecuted("checkClassLoader")

        where:
        isolationMode << ISOLATION_MODES
    }

    void withParameterClassReferencingClassInAnotherPackage() {
        file("buildSrc/src/main/java/org/gradle/another/Bar.java").text = """
            package org.gradle.another;
            
            import java.io.Serializable;
            
            public class Bar implements Serializable { }
        """

        file("buildSrc/src/main/java/org/gradle/other/Foo.java").text = """
            package org.gradle.other;

            import java.io.Serializable;
            import org.gradle.another.Bar;

            public class Foo implements Serializable { 
                Bar bar = new Bar();
            }
        """
    }

    void withWorkActionClassInExternalJar(File workActionJar) {
        file("buildSrc").deleteDir()

        def builder = artifactBuilder()
        fixture.workActionThatCreatesFiles.writeToFile builder.sourceFile("org/gradle/test/TestWorkAction.java")
        fixture.testParameterType.writeToFile builder.sourceFile("org/gradle/test/TestParameters.java")

        builder.sourceFile("org/gradle/other/Foo.java") << """
            $fixture.parameterClass
        """
        builder.sourceFile('AnotherFoo.java') << """
            class AnotherFoo extends org.gradle.other.Foo { }
        """
        builder.sourceFile("org/gradle/test/FileHelper.java") << """
            $fixture.fileHelperClass
        """
        builder.buildJar(workActionJar)

        fixture.addImportToBuildScript("org.gradle.test.TestWorkAction")
    }
}
