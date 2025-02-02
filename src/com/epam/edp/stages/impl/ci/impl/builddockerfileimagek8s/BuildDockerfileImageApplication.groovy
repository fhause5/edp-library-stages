/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/
package com.epam.edp.stages.impl.ci.impl.builddockerfileimagek8s

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import com.epam.edp.stages.impl.ci.ProjectType
import com.epam.edp.stages.impl.ci.Stage
import hudson.FilePath

@Stage(name = "build-image-from-dockerfile-k8s", buildTool = ["maven", "npm", "gradle", "dotnet"], type = [ProjectType.APPLICATION])
class BuildDockerfileImageApplication {
    Script script

    def setEnvVariable(envList, name, value, overwrite = false) {
        if (envList.find { it.name == name } && overwrite)
            envList.find { it.name == name }.value = value
        else
            envList.add(['name': name, 'value': value])
    }

    def setKanikoTemplate(outputFilePath, buildPodName, resultImageName, dockerRegistry, context) {
        def kanikoTemplateFilePath = new FilePath(Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(), outputFilePath)
        def kanikoTemplateData = context.platform.getJsonPathValue("cm", "kaniko-template", ".data.kaniko\\.json")
        def parsedKanikoTemplateData = new JsonSlurperClassic().parseText(kanikoTemplateData)
        parsedKanikoTemplateData.metadata.name = buildPodName

        def awsCliInitContainerEnvs = parsedKanikoTemplateData.spec.initContainers[1].env
        setEnvVariable(awsCliInitContainerEnvs, "REPO_NAME", resultImageName, true)
        setEnvVariable(awsCliInitContainerEnvs, "AWS_DEFAULT_REGION", dockerRegistry.region)

        dockerRegistry.region = awsCliInitContainerEnvs.find { it.name == "AWS_DEFAULT_REGION" }.value
        dockerRegistry.host = "${dockerRegistry.accountId}.dkr.ecr.${dockerRegistry.region}.amazonaws.com"
        parsedKanikoTemplateData.spec.containers[0].args[0] = "--destination=${dockerRegistry.host}/${resultImageName}:${context.codebase.buildVersion}"
        def jsonData = JsonOutput.toJson(parsedKanikoTemplateData)
        kanikoTemplateFilePath.write(jsonData, null)
        return kanikoTemplateFilePath
    }

    def getDockerRegistryInfo() {
        def dockerRegistry = [:]
        try {
            def response = script.httpRequest timeout: 10, url: 'http://169.254.169.254/latest/dynamic/instance-identity/document'
            def parsedMetadata = new JsonSlurperClassic().parseText(response.content)
            dockerRegistry.accountId = "${parsedMetadata.accountId}"
            dockerRegistry.region = parsedMetadata.region
            return dockerRegistry
        }
        catch (Exception ex) {
            return null
        }
    }

    def setCodebaseImageStreamTemplate(outputFilePath, cbisName, fullImageName, context) {
        def cbisTemplateFilePath = new FilePath(Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(), outputFilePath)
        def cbisTemplateData = context.platform.getJsonPathValue("cm", "cbis-template", ".data.cbis\\.json")
        def parsedCbisTemplateData = new JsonSlurperClassic().parseText(cbisTemplateData)
        parsedCbisTemplateData.metadata.name = cbisName
        parsedCbisTemplateData.spec.imageName = fullImageName

        def jsonData = JsonOutput.toJson(parsedCbisTemplateData)
        cbisTemplateFilePath.write(jsonData, null)
        return cbisTemplateFilePath
    }

    def updateCodebaseimagestreams(cbisName, repositoryName, imageTag, context) {
        def crApiGroup = "${context.job.getParameterValue("GIT_SERVER_CR_VERSION")}.edp.epam.com"
        if (!context.platform.checkObjectExists("cbis.${crApiGroup}", cbisName)) {
            script.println("[JENKINS][DEBUG] CodebaseImagestream not found. Creating new CodebaseImagestream")
            def cbisTemplateFilePath = setCodebaseImageStreamTemplate("${context.workDir}/cbis-template.json", cbisName, repositoryName, context)
            context.platform.apply(cbisTemplateFilePath.getRemote())
        }
        def cbisResource = context.platform.getJsonValue("cbis.${crApiGroup}", cbisName)
        def parsedCbisResource = new JsonSlurperClassic().parseText(cbisResource)
        def cbisTags = parsedCbisResource.spec.tags ? parsedCbisResource.spec.tags : []

        if (!cbisTags.find{ it.name == imageTag }) {
            cbisTags.add(['name':imageTag])
            def newCbisTags = JsonOutput.toJson(cbisTags)
            script.sh("kubectl patch --type=merge cbis.${crApiGroup} ${cbisName} -p '{\"spec\":{\"tags\":${newCbisTags}}}'")
        }
    }

    void run(context) {
        def dockerfilePath = new FilePath(Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(),
                "${context.workDir}/Dockerfile")
        if (!dockerfilePath.exists()) {
            script.error("[JENKINS][ERROR] There is no Dockerfile in the root of the repository, we are not able to perform build image")
        }

        def resultImageName = "${context.codebase.name}-${context.git.branch.replaceAll("[^\\p{L}\\p{Nd}]+", "-")}"
        def buildconfigName = "build-${resultImageName}-${script.BUILD_NUMBER}"
        script.dir("${context.workDir}") {
            try {
                def dockerRegistry = getDockerRegistryInfo()
                if (!dockerRegistry)
                    script.error("[JENKINS][ERROR] Couldn't get docker registry server")

                def kanikoTemplateFilePath = setKanikoTemplate("${context.workDir}/kaniko-template.json", buildconfigName, resultImageName, dockerRegistry, context)
                context.platform.apply(kanikoTemplateFilePath.getRemote())
                while (!context.platform.getObjectStatus("pod", buildconfigName)["initContainerStatuses"][0].state.keySet().contains("running")) {
                    script.println("[JENKINS][DEBUG] Waiting for init container in Kaniko is started")
                    script.sleep(5)
                }

                def deployableModuleDirFilepath = new FilePath(Jenkins.getInstance().getComputer(script.env['NODE_NAME']).getChannel(), context.codebase.deployableModuleDir)
                deployableModuleDirFilepath.list().each() { item ->
                    if (item.getName() != "Dockerfile")
                        context.platform.copyToPod("${context.codebase.deployableModuleDir}/${item.getName()}", "/tmp/workspace/", buildconfigName, null, "init-kaniko")
                }
                context.platform.copyToPod("Dockerfile", "/tmp/workspace", buildconfigName, null, "init-kaniko")

                while (context.platform.getObjectStatus("pod", buildconfigName).phase != "Succeeded") {
                    if (context.platform.getObjectStatus("pod", buildconfigName).phase == "Failed")
                        script.error("[JENKINS][ERROR] Build pod ${buildconfigName} failed")
                    script.println("[JENKINS][DEBUG] Waiting for build ${buildconfigName}")
                    script.sleep(10)
                }

                script.println("[JENKINS][DEBUG] Build config ${buildconfigName} for application ${context.codebase.name} has been completed")

                updateCodebaseimagestreams(resultImageName, "${dockerRegistry.host}/${resultImageName}", context.codebase.buildVersion, context)
            }
            catch (Exception ex) {
                script.error("[JENKINS][ERROR] Building image for ${context.codebase.name} failed")
            }
            finally {
                def podToDelete = "build-${context.codebase.name}-${context.git.branch.replaceAll("[^\\p{L}\\p{Nd}]+", "-")}-${script.BUILD_NUMBER.toInteger() - 1}"
                context.platform.deleteObject("pod", podToDelete, true)
            }
        }
    }
}