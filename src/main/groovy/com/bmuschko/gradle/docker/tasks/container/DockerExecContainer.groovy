/*
 * Copyright 2014 the original author or authors.
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
package com.bmuschko.gradle.docker.tasks.container

import com.bmuschko.gradle.docker.domain.ExecProbe
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.ExecCreateCmd
import com.github.dockerjava.api.command.InspectExecResponse
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.internal.logging.progress.ProgressLoggerFactory

import java.util.concurrent.TimeUnit

import static com.bmuschko.gradle.docker.internal.IOUtils.getProgressLogger
import static com.bmuschko.gradle.docker.internal.IOUtils.getProgressLoggerFactory

@CompileStatic
class DockerExecContainer extends DockerExistingContainer {

    @Input
    @Optional
    final ListProperty<String[]> commands = project.objects.listProperty(String[])

    @Input
    @Optional
    final Property<Boolean> attachStdout = project.objects.property(Boolean)

    @Input
    @Optional
    final Property<Boolean> attachStderr = project.objects.property(Boolean)

    /**
     * Username or UID to execute the command as, with optional colon separated group or gid in format:
     * &lt;name|uid&gt;[:&lt;group|gid&gt;]
     *
     * @since 3.2.8
     */
    @Input
    @Optional
    final Property<String> user = project.objects.property(String)

    /**
     * Working directory in which the command is going to be executed.
     * Defaults to the WORKDIR set in docker file.
     *
     * @since 6.3.0
     */
    @Input
    @Optional
    final Property<String> workingDir = project.objects.property(String)

    // if set will check exit code of exec to ensure it's
    // within this list of allowed values otherwise through exception
    @Input
    @Optional
    final ListProperty<Integer> successOnExitCodes = project.objects.listProperty(Integer)

    // if `successOnExitCodes` are defined this livenessProbe will poll the "exec response"
    // until it is in a non-running state.
    @Nested
    @Optional
    ExecProbe execProbe

    @Internal
    final ListProperty<String> execIds = project.objects.listProperty(String)

    DockerExecContainer() {
        commands.empty()
        attachStdout.set(true)
        attachStderr.set(true)
        successOnExitCodes.empty()
        execIds.empty()
    }

    @Override
    void runRemoteCommand() {
        logger.quiet "Executing on container with ID '${containerId.get()}'."
        doRunRemoteCommand(dockerClient)
    }

    private final ProgressLoggerFactory progressLoggerFactory = getProgressLoggerFactory(project)

    protected void doRunRemoteCommand(DockerClient dockerClient) {
        def execCallback = createCallback(nextHandler)

        List<String[]> localCommands = commands.get()
        for (int i = 0; i < commands.get().size(); i++) {

            String [] singleCommand = localCommands.get(i)
            ExecCreateCmd execCmd = dockerClient.execCreateCmd(containerId.get())
            setContainerCommandConfig(execCmd, singleCommand)
            String localExecId = execCmd.exec().getId()
            dockerClient.execStartCmd(localExecId).withDetach(false).exec(execCallback).awaitCompletion()


            // create progressLogger for pretty printing of terminal log progression.
            final def progressLogger = getProgressLogger(progressLoggerFactory, DockerExecContainer)
            progressLogger.started()

            // if no livenessProbe defined then create a default
            final ExecProbe localProbe = execProbe ?: new ExecProbe(60000, 2000)

            long localPollTime = localProbe.pollTime
            int pollTimes = 0
            boolean isRunning = true

            // 3.) poll for some amount of time until container is in a non-running state.
            InspectExecResponse lastExecResponse
            while (isRunning && localPollTime > 0) {
                pollTimes += 1

                lastExecResponse = dockerClient.inspectExecCmd(localExecId).exec()
                isRunning = lastExecResponse.running
                if (isRunning) {

                    long totalMillis = pollTimes * localProbe.pollInterval
                    long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis)
                    progressLogger.progress("Executing for ${totalMinutes}m...")
                    try {

                        localPollTime -= localProbe.pollInterval
                        sleep(localProbe.pollInterval)
                    } catch (Exception e) {
                        throw e
                    }
                } else {
                    break
                }
            }
            progressLogger.completed()

            // if still running then throw an exception otherwise check the exitCode
            if (isRunning) {
                throw new GradleException("Exec '${singleCommand}' did not finish in a timely fashion: ${localProbe}")
            } else {
                if (successOnExitCodes.getOrNull()) {
                    int exitCode = lastExecResponse.exitCode ?: 0
                    if (!successOnExitCodes.get().contains(exitCode)) {
                        throw new GradleException("${exitCode} is not a successful exit code. Valid values are ${successOnExitCodes.get()}, response=${lastExecResponse}")
                    }
                }
            }

            execIds.add(localExecId)
        }
    }

    // add multiple commands to be executed
    void withCommand(List<String> commandsToExecute) {
        withCommand(commandsToExecute as String[])
    }

    void withCommand(String[] commandsToExecute) {
        if (commandsToExecute) {
            commands.add(commandsToExecute)
        }
    }

    private void setContainerCommandConfig(ExecCreateCmd containerCommand, String[] commandToExecute) {
        if (commandToExecute) {
            containerCommand.withCmd(commandToExecute)
        }

        if (attachStderr.getOrNull()) {
            containerCommand.withAttachStderr(attachStderr.get())
        }

        if (attachStdout.getOrNull()) {
            containerCommand.withAttachStdout(attachStdout.get())
        }

        if (user.getOrNull()) {
            containerCommand.withUser(user.get())
        }

        if (workingDir.getOrNull()) {
            containerCommand.withWorkingDir(workingDir.get())
        }
    }

    /**
     * Define the livenessProbe options for this exec.
     *
     * @param pollTime how long we will poll for
     * @param pollInterval interval between poll requests
     * @return instance of ExecProbe
     */
    def execProbe(final long pollTime, final long pollInterval) {
        this.execProbe = new ExecProbe(pollTime, pollInterval)
    }

    private ResultCallback.Adapter<Frame> createCallback(Action nextHandler) {
        if (nextHandler) {
            return new ResultCallback.Adapter<Frame>() {
                @Override
                void onNext(Frame frame) {
                    try {
                        nextHandler.execute(frame)
                    } catch (Exception e) {
                        logger.error('Failed to handle frame', e)
                        return
                    }
                    super.onNext(frame)
                }
            }
        }

        new ResultCallback.Adapter<Frame>() {
            @Override
            void onNext(Frame frame) {
                if (frame != null) {
                    switch (frame.getStreamType()) {
                        case StreamType.STDOUT:
                        case StreamType.RAW:
                            System.out.write(frame.getPayload())
                            System.out.flush()
                            break
                        case StreamType.STDERR:
                            System.err.write(frame.getPayload())
                            System.err.flush()
                            break
                        default:
                            getLogger().error("unknown stream type:" + frame.getStreamType())
                    }
                }
            }
        }
    }
}
