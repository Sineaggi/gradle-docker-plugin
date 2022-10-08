package com.bmuschko.gradle.docker.tasks

import com.bmuschko.gradle.docker.AbstractGroovyDslFunctionalTest
import org.gradle.testkit.runner.BuildResult

class DockerVersionFunctionalTest extends AbstractGroovyDslFunctionalTest {
    def "Can enable configuration cache and retrieve Docker version"() {
        given:
        buildFile << """
        import com.bmuschko.gradle.docker.tasks.DockerVersion
        tasks.register("version", DockerVersion)
"""
        when:
        BuildResult result = build("--configuration-cache", "version")

        then:
        result.output.contains("0 problems were found storing the configuration cache.")

        when:
        result = build("--configuration-cache", "version")

        then:
        result.output.contains("Configuration cache entry reused.")
    }
}
