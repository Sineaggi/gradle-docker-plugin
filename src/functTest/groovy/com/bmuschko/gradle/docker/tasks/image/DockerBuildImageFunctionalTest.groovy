package com.bmuschko.gradle.docker.tasks.image

import com.bmuschko.gradle.docker.AbstractGroovyDslFunctionalTest
import com.bmuschko.gradle.docker.TestConfiguration
import com.bmuschko.gradle.docker.TestPrecondition
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Unroll

class DockerBuildImageFunctionalTest extends AbstractGroovyDslFunctionalTest {

    def "prints error message when image build fails"() {
        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
                addFile(new Dockerfile.File('./aaa', 'aaa'))
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn dockerfile
            }
        """

        when:
        def result = buildAndFail(CONFIGURATION_CACHE, 'buildImage')

        then:
        result.output.contains("ADD failed: file not found in build context or excluded by .dockerignore: stat aaa: file does not exist")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image"() {
        buildFile << imageCreationTask()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImage')

        then:
        result.output.contains("Created image with ID")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image using an argument"() {
        new File(projectDir, 'Dockerfile') << """FROM '$TEST_IMAGE_WITH_TAG'
ARG user
USER \$user"""
        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerExistingImage
            import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage

            task buildImage(type: DockerBuildImage) {
                inputDir = projectDir
                dockerFile = file('Dockerfile')
                buildArgs = ['user': 'what_user']
            }

            task inspectImage(type: DockerInspectImageUser) {
                dependsOn buildImage
                imageId = buildImage.imageId
            }

            task removeImage(type: DockerRemoveImage) {
                force = true
                imageId = buildImage.imageId
            }

            inspectImage.finalizedBy tasks.removeImage

            class DockerInspectImageUser extends DockerExistingImage {
                DockerInspectImageUser() {
                    onNext({ image ->
                        logger.quiet "user: \$image.containerConfig.user"
                    })
                }

                @Override
                void runRemoteCommand() {
                    def image = dockerClient.inspectImageCmd(imageId.get()).exec()
                    nextHandler.execute(image)
                }
            }
        """

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'inspectImage')

        then:
        result.output.contains("Created image with ID")
        result.output.contains("user: what_user")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image for a specific platform"() {
        buildFile << imageCreationTask()
        buildFile << "buildImage.platform = 'linux/s390x'"

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImage')

        then:
        result.output.contains("Created image with ID")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image with the specified amount allocated memory"() {
        buildFile << imageCreationTask()
        buildFile << "buildImage.memory = 1073741824L"

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImage')

        then:
        result.output.contains("Created image with ID")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    @IgnoreIf({ os.windows })
    def "can build image in a parent context"() {
        buildFile << imageCreationTask()
        buildFile << """
            buildImage {
                inputDir = projectDir
                dockerFile = dockerfile.destFile
            }
        """

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImage')

        then:
        result.output.contains("Created image with ID")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    @Unroll
    def "can build image with labels for #description"() {
        buildFile << gradleTaskDefinition

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'inspectImage')

        then:
        result.output.contains("label1:test1, label2:test2")

        where:
        description        | gradleTaskDefinition
        'build arguments'  | imageCreationWithBuildArgsTask()
        'label parameters' | imageCreationWithLabelParameterTask()
    }

    def "labels can be excluded from up-to-date check"() {
        given:
        buildFile << dockerFileTask() << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            task buildImage(type: CustomDockerBuildImage) {
                dependsOn dockerfile
                labels = ["build-date": "\${getBuildDate()}"]
            }

            def getBuildDate() {
                return new Date().format('yyyyMMddHHmmss.SSS')
            }

            class CustomDockerBuildImage extends DockerBuildImage {
                @Override
                @Internal
                MapProperty<String, String> getLabels() {
                    super.getLabels()
                }
            }
        """

        when:
        BuildResult result = build('buildImage')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS

        when:
        result = build('buildImage')

        then:
        result.task(':buildImage').outcome == TaskOutcome.UP_TO_DATE
    }

    def "can build image with multiple tags"() {
        buildFile << buildImageWithTagsTask()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImageWithTags', 'buildImageWithTag')

        then:
        result.output.contains("Using images 'test/image:123', 'registry.com:5000/test/image:123'.")
        result.output.contains("Using images 'test/image:123'.")
        result.output.contains("0 problems were found storing the configuration cache.")
    }


    def "can set /dev/shm size for image build"() {
        buildFile << buildImageWithShmSize()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, "buildWithShmSize")

        then:
        noExceptionThrown()
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image with different targets"() {
        buildFile << buildMultiStageImageTask()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildTarget')

        then:
        // check the output for the built stages
        result.output.contains('Step 2/4 : LABEL maintainer=stage1')
        result.output.contains('Step 4/4 : LABEL maintainer=stage2')
        // stage3 was not called
        ! result.output.contains('stage3')
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image using --cache-from with nothing in the cache"() {
        buildFile << buildImageUsingCacheFromWithNothingInCacheTask()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImageWithCacheFrom')

        then:
        result.output.contains("Successfully built")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    @Requires({ TestPrecondition.DOCKER_PRIVATE_REGISTRY_REACHABLE })
    def "cache is not used for build without --cached-from"() {
        buildFile << buildPushRemovePullBuildImage(false)

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImageWithCacheFrom')

        then:
        !result.output.contains("Using cache")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    @Requires({ TestPrecondition.DOCKER_PRIVATE_REGISTRY_REACHABLE })
    def "cache is used for build using --cached-from"() {
        buildFile << buildPushRemovePullBuildImage(true)

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImageWithCacheFrom')

        then:
        result.output.contains("Using cache")
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "can build image using --network host"() {
        buildFile << buildImageWithHostNetworkTask()

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildWithHostNetwork')

        then:
        noExceptionThrown()
        result.output.contains("0 problems were found storing the configuration cache.")
    }

    def "task can be up-to-date"() {
        given:
        buildFile << imageCreationTask()
        buildFile << """
            task verifyImageId {
                dependsOn buildImage
            }
        """
        File imageIdFile = new File(projectDir, 'build/.docker/buildImage-imageId.txt')

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""
        result.output.contains("0 problems were found storing the configuration cache.")

        when:
        result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.UP_TO_DATE
        !result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""
        result.output.contains("Reusing configuration cache.")
    }

    def "task not up-to-date when no imageIdFile"() {
        given:
        buildFile << imageCreationTask()
        buildFile << """
            task verifyImageId {
                dependsOn buildImage
            }
        """
        File imageIdFile = new File(projectDir, 'build/.docker/buildImage-imageId.txt')

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.UP_TO_DATE
        !result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        imageIdFile.delete()
        result = build('verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""
    }

    def "task not up-to-date when image not in registry"() {
        given:
        buildFile << imageCreationTask()
        buildFile << """
            task verifyImageId {
                dependsOn buildImage
            }
        """
        File imageIdFile = new File(projectDir, 'build/.docker/buildImage-imageId.txt')

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.UP_TO_DATE
        !result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        buildFile << imageRemovalTask(imageIdFile.text)
        result = build(CONFIGURATION_CACHE, 'removeImage')

        then:
        result.task(':removeImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Removing image with ID")

        when:
        result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""
    }

    @Issue('https://github.com/bmuschko/gradle-docker-plugin/issues/818')
    def "task not up-to-date when imageIdFile changed"() {
        given:
        buildFile << imageCreationTask()
        buildFile << """
            task verifyImageId {
                dependsOn buildImage
            }
        """
        File imageIdFile = new File(projectDir, 'build/.docker/buildImage-imageId.txt')

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        result = build(CONFIGURATION_CACHE, 'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.UP_TO_DATE
        !result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""

        when:
        imageIdFile << " "
        result = build(CONFIGURATION_CACHE ,'verifyImageId')

        then:
        result.task(':buildImage').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
        imageIdFile.isFile()
        imageIdFile.text != ""
    }

    def "task not up-to-date when imageId is not tagged as configured"() {
        given:
        buildFile << buildImageWithTagsTask()
        buildFile << """
            import com.bmuschko.gradle.docker.tasks.image.DockerTagImage
            import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage
            import com.bmuschko.gradle.docker.tasks.DockerOperation

            task retagImage(type: DockerTagImage) {
                dependsOn buildImageWithTag
                imageId = buildImageWithTag.imageId
                repository = 'test/image'
                tag = 'foo'
            }

            task deleteOriginalTag(type: DockerRemoveImage) {
                dependsOn retagImage
                imageId = buildImageWithTag.images.get().first()
            }

            task verifyTagsMissing(type: DockerOperation) {
                dependsOn deleteOriginalTag
                onNext {
                    if (inspectImageCmd(buildImageWithTag.imageId.get()).exec().repoTags.containsAll(buildImageWithTag.images.get())) {
                        throw new GradleException("There should be configured tags missing now")
                    }
                }
            }

            task verifyTagsPresent(type: DockerOperation) {
                dependsOn buildImageWithTag
                onNext {
                    if (!inspectImageCmd(buildImageWithTag.imageId.get()).exec().repoTags.containsAll(buildImageWithTag.images.get())) {
                        throw new GradleException("All configured tags should be present now")
                    }
                }
            }
        """

        when:
        BuildResult result = build(CONFIGURATION_CACHE, 'buildImageWithTag')

        then:
        result.task(':buildImageWithTag').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")

        when:
        result = build(CONFIGURATION_CACHE, 'verifyTagsMissing')

        then:
        result.task(':buildImageWithTag').outcome == TaskOutcome.UP_TO_DATE
        result.task(':retagImage').outcome == TaskOutcome.SUCCESS
        result.task(':deleteOriginalTag').outcome == TaskOutcome.SUCCESS
        result.task(':verifyTagsMissing').outcome == TaskOutcome.SUCCESS
        !result.output.contains("Created image with ID")

        when:
        result = build(CONFIGURATION_CACHE, 'verifyTagsPresent')

        then:
        result.task(':buildImageWithTag').outcome == TaskOutcome.SUCCESS
        result.task(':verifyTagsPresent').outcome == TaskOutcome.SUCCESS
        result.output.contains("Created image with ID")
    }

    private static String buildImageWithShmSize() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            task dockerfile(type: Dockerfile) {
                from 'alpine'
            }

            task buildWithShmSize(type: DockerBuildImage) {
                dependsOn dockerfile
                shmSize = 128000L
            }

            ${imageIdValidation()}
        """
    }

    private static String dockerFileTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
                runCommand("echo ${UUID.randomUUID()}")
            }
        """
    }


    private static String imageCreationTask() {
        dockerFileTask() << """
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            task buildImage(type: DockerBuildImage) {
                dependsOn dockerfile
            }

            ${imageIdValidation()}
        """
    }

    private static String imageRemovalTask(String imageId) {
        """
            import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage

            task removeImage(type: DockerRemoveImage) {
                force = true
                targetImageId '$imageId'
            }
        """
    }

    private static String imageCreationWithBuildArgsTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerInspectImage

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'

                arg('arg1')
                arg('arg2')

                label(['label1':'\$arg1', 'label2':'\$arg2'])
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn dockerfile
                buildArgs = ['arg1':'test1', 'arg2':'test2', 'arg3': "\$project.name"]
            }

            task inspectImage(type: DockerInspectImage) {
                dependsOn buildImage
                targetImageId buildImage.getImageId()
            }

            ${imageIdValidation()}
        """
    }

    private static String imageCreationWithLabelParameterTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerInspectImage

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn dockerfile
                labels = ['label1':'test1', 'label2':'test2', 'label3':"\$project.name"]
            }

            task inspectImage(type: DockerInspectImage) {
                dependsOn buildImage
                targetImageId buildImage.getImageId()
            }

            ${imageIdValidation()}
        """
    }

    private static String buildImageWithTagsTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            project.version = "123"

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
            }

            task buildImageWithTags(type: DockerBuildImage) {
                dependsOn dockerfile
                images = ['test/image:123', "registry.com:5000/test/image:\$project.version"]
            }

            task buildImageWithTag(type: DockerBuildImage) {
                dependsOn dockerfile
                images.add('test/image:123')
            }

            ${imageIdValidation()}
        """
    }

    private static String buildImageUsingCacheFromWithNothingInCacheTask() {
        def uniqueImageId = createUniqueImageId()
        def uniqueTag = "${TestConfiguration.dockerPrivateRegistryDomain}/$uniqueImageId"
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            project.version = "123"

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
                label(['maintainer': '${UUID.randomUUID().toString()}'])
            }

            task buildImageWithCacheFrom(type: DockerBuildImage) {
                dependsOn dockerfile
                cacheFrom.add('$uniqueTag:latest')
                images.add('$uniqueTag:latest')
            }

            ${imageIdValidation()}
        """
    }

    private static String buildPushRemovePullBuildImage(boolean useCacheFrom) {
        def uniqueImageId = createUniqueImageId()
        def uniqueTag = "${TestConfiguration.dockerPrivateRegistryDomain}/$uniqueImageId"
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
            import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage
            import com.bmuschko.gradle.docker.tasks.image.DockerPullImage

            project.version = "123"

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
                label(['maintainer': '${UUID.randomUUID().toString()}'])
            }

            task buildImage(type: DockerBuildImage) {
                dependsOn dockerfile
                cacheFrom.add('$TEST_IMAGE_WITH_TAG') // no effect
                images.add('$uniqueTag')
            }

            task pushImage(type: DockerPushImage) {
                dependsOn buildImage
                images.set(buildImage.images)
            }

            task removeImage(type: DockerRemoveImage) {
                dependsOn pushImage
                force = true
                targetImageId buildImage.getImageId()
            }

            task pullImage(type: DockerPullImage) {
                dependsOn removeImage
                image = '$uniqueTag:latest'
            }

            task buildImageWithCacheFrom(type: DockerBuildImage) {
                dependsOn pullImage
                inputDir = dockerfile.destFile.get().asFile.parentFile
                ${useCacheFrom ? "cacheFrom.add('$uniqueTag:latest')" : ""}
            }

            ${imageIdValidation()}
        """
    }

    private static String buildImageWithHostNetworkTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

            task dockerfile(type: Dockerfile) {
                from '$TEST_IMAGE_WITH_TAG'
            }

            task buildWithHostNetwork(type: DockerBuildImage) {
                dependsOn dockerfile
                network = 'host'
            }

            ${imageIdValidation()}
        """
    }

    private static String buildMultiStageImageTask() {
        """
            import com.bmuschko.gradle.docker.tasks.image.Dockerfile
            import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
            import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage

            task dockerfile(type: Dockerfile) {
                from(new Dockerfile.From('$TEST_IMAGE_WITH_TAG').withStage('stage1'))
                label(['maintainer': 'stage1'])

                from(new Dockerfile.From('$TEST_IMAGE_WITH_TAG').withStage('stage2'))
                label(['maintainer': 'stage2'])

                from(new Dockerfile.From('$TEST_IMAGE_WITH_TAG').withStage('stage3'))
                label(['maintainer': 'stage3'])
            }

            task buildTarget(type: DockerBuildImage) {
                dependsOn dockerfile
                target = "stage2"
            }

            task removeImage(type: DockerRemoveImage) {
                force = true
                targetImageId buildTarget.getImageId()
            }

            buildTarget.finalizedBy tasks.removeImage

            ${imageIdValidation()}
        """
    }

    private static String imageIdValidation() {
        """
            tasks.withType(DockerBuildImage) { Task task ->
                def assertTask = tasks.create("assertImageIdFor\${task.name.capitalize()}"){
                    def imageId = task.getImageId()
                    doLast {
                        def value = imageId.getOrNull()
                        if(value == null || !(value ==~ /^\\w+\$/)) {
                            throw new GradleException("The imageId property was not set from task \${dependantTask.name}")
                        }
                    }
                }

                task.finalizedBy assertTask
            }
        """
    }
}
