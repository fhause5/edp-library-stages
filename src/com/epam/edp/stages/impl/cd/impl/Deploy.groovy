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

package com.epam.edp.stages.impl.cd.impl

import com.epam.edp.stages.impl.cd.Stage
import org.apache.commons.lang.RandomStringUtils

@Stage(name = "deploy")
class Deploy {
    Script script

    def getBuildUserFromLog(context) {
        def jenkinsCred = "admin:${context.jenkins.token}".bytes.encodeBase64().toString()
        def jobUrl = "${context.job.buildUrl}".replaceFirst("${context.job.jenkinsUrl}", '')
        def response = script.httpRequest url: "http://jenkins.${context.job.edpName}-edp-cicd:8080/${jobUrl}consoleText",
                httpMode: 'GET',
                customHeaders: [[name: 'Authorization', value: "Basic ${jenkinsCred}"]]
        return script.sh(
                script: "#!/bin/sh -e\necho \"${response.content}\" | grep \"Approved by\" -m 1 | awk {'print \$3'}",
                returnStdout: true
        ).trim()
    }

    def checkOpenshiftTemplateExists(context, templateName) {
        if (!script.openshift.selector("template", templateName).exists()) {
            script.println("[JENKINS][WARNING] Template which called ${templateName} doesn't exist in ${context.job.edpName}-edp-cicd namespace")
            return false
        }
        return true
    }

    def deployConfigMaps(codebaseDir, name, context) {
        File folder = new File("${codebaseDir}/config-files")
        for (file in folder.listFiles()) {
            if (file.isFile() && file.getName() == "Readme.md")
                continue
            String configsDir = file.getName().split("\\.")[0].replaceAll("[^\\p{L}\\p{Nd}]+", "-").toLowerCase()
            context.platform.createConfigMapFromFile("${name}-${configsDir}", context.job.deployProject, "${codebaseDir}/config-files/${file.getName()}")
            script.println("[JENKINS][DEBUG] Configmap ${configsDir} has been created")
        }
    }

    def checkDeployment(context, object, type) {
        script.println("[JENKINS][DEBUG] Validate deployment - ${object.name} in ${context.job.deployProject}")
        try {
            context.platform.verifyDeployedCodebase(object.name, context.job.deployProject)
            if (type == 'application' && getDeploymentVersion(context, object) != object.currentDeploymentVersion) {
                script.println("[JENKINS][DEBUG] Deployment ${object.name} in project ${context.job.deployProject} has been rolled out")
            } else
                script.println("[JENKINS][DEBUG] New version of codebase ${object.name} hasn't been deployed, because the same version")
        }
        catch (Exception verifyDeploymentException) {
            if (type == "application" && object.currentDeploymentVersion != 0) {
                script.println("[JENKINS][WARNING] Rolling out of ${object.name} with version ${object.version} has been failed.\r\n" +
                        "[JENKINS][WARNING] Rolling back to the previous version")
                context.platform.rollbackDeployedCodebase(object.name, context.job.deployProject)
                context.platform.verifyDeployedCodebase(object.name, context.job.deployProject)
                script.println("[JENKINS][WARNING] Rolling out of ${object.name} with version ${object.version} has been failed.")
            } else
                script.println("[JENKINS][WARNING] ${object.name} deploy has been failed. Reason - ${verifyDeploymentException}")
            throw(verifyDeploymentException)
        }
    }

    def getDeploymentVersion(context, codebase) {
        if (!context.platform.checkObjectExists("dc", codebase.name, context.job.deployProject)) {
            script.println("[JENKINS][WARNING] Deployment config ${codebase.name} doesn't exist in the project ${context.job.deployProject}\r\n" +
                    "[JENKINS][WARNING] We will roll it out")
            return null
        }
        def version = context.platform.getJsonPathValue("dc", codebase.name, ".status.latestVersion", context.job.deployProject)
        return (version.toInteger())
    }

    def checkImageExists(context, object) {
        def imageExists = context.platform.getImageStream(object.normalizedName, context.job.crApiVersion)
        if (imageExists == "") {
            script.println("[JENKINS][WARNING] Image stream ${object.name} doesn't exist in the project ${context.job.metaProject}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped")
            return false
        }

        def tagExist = context.platform.getImageStreamTags(object.normalizedName, context.job.crApiVersion)
        if (!tagExist) {
            script.println("[JENKINS][WARNING] Image stream ${object.name} with tag ${object.version} doesn't exist in the project ${context.job.metaProject}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped")
            return false
        }
        return true
    }

    def getNumericVersion(context, codebase) {
        def hash = script.sh(
                script: "oc -n ${context.job.metaProject} get is ${codebase.normalizedName} -o jsonpath=\'{@.spec.tags[?(@.name==\"${codebase.version}\")].from.name}\'",
                returnStdout: true
        ).trim()
        def tags = script.sh(
                script: "oc -n ${context.job.metaProject} get is ${codebase.normalizedName} -o jsonpath=\'{@.spec.tags[?(@.from.name==\"${hash}\")].name}\'",
                returnStdout: true
        ).trim().tokenize()
        tags.removeAll { it == "latest" }
        tags.removeAll { it == "stable" }
        script.println("[JENKINS][DEBUG] Codebase ${codebase.name} has the following numeric tag, which corresponds to tag ${codebase.version} - ${tags}")
        switch (tags.size()) {
            case 0:
                script.println("[JENKINS][WARNING] Codebase ${codebase.name} has no numeric version for tag ${codebase.version}\r\n" +
                        "[JENKINS][WARNING] Deploy will be skipped")
                return null
                break
            case 1:
                return (tags[0])
                break
            default:
                script.println("[JENKINS][WARNING] Codebase ${codebase.name} has more than one numeric tag for tag ${codebase.version}\r\n" +
                        "[JENKINS][WARNING] We will use the first one")
                return (tags[0])
                break
        }
    }

    def getRepositoryPath(codebase) {
        if (codebase.strategy == "import") {
            return codebase.gitProjectPath
        }
        return "/" + codebase.name
    }

    def cloneProject(context, codebase) {
        script.println("[JENKINS][DEBUG] Start fetching Git Server info for ${codebase.name} from ${codebase.gitServer} CR")

        def gitServerName = "gitservers.${context.job.crApiVersion}.edp.epam.com"

        script.println("[JENKINS][DEBUG] Git Server CR Version: ${context.job.crApiVersion}")
        script.println("[JENKINS][DEBUG] Git Server Name: ${gitServerName}")

        def autouser = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.gitUser")
        def host = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.gitHost")
        def sshPort = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.sshPort")
        def credentialsId = context.platform.getJsonPathValue(gitServerName, codebase.gitServer, ".spec.nameSshKeySecret")

        script.println("[JENKINS][DEBUG] autouser: ${autouser}")
        script.println("[JENKINS][DEBUG] host: ${host}")
        script.println("[JENKINS][DEBUG] sshPort: ${sshPort}")
        script.println("[JENKINS][DEBUG] credentialsId: ${credentialsId}")

        def repoPath = getRepositoryPath(codebase)
        script.println("[JENKINS][DEBUG] Repository path: ${repoPath}")

        def gitCodebaseUrl = "ssh://${autouser}@${host}:${sshPort}${repoPath}"

        try {
            script.checkout([$class                           : 'GitSCM', branches: [[name: "refs/tags/${codebase.branch}-${codebase.version}"]],
                             doGenerateSubmoduleConfigurations: false, extensions: [],
                             submoduleCfg                     : [],
                             userRemoteConfigs                : [[credentialsId: "${credentialsId}",
                                                                  refspec      : "refs/tags/${codebase.branch}-${codebase.version}",
                                                                  url          : "${gitCodebaseUrl}"]]])
        }
        catch (Exception ex) {
            script.unstable("[JENKINS][WARNING] Project ${codebase.name} cloning has failed with ${ex}\r\n" +
                    "[JENKINS][WARNING] Deploy will be skipped\r\n" +
                    "[JENKINS][WARNING] Check if tag ${codebase.version} exists in repository")
            script.currentBuild.setResult('UNSTABLE')
            script.currentBuild.description = "${script.currentBuild.description}\r\n${codebase.name} deploy failed"
            return false
        }
        script.println("[JENKINS][DEBUG] Project ${codebase.name} has been successfully cloned")
        return true
    }

    def deployCodebaseTemplate(context, codebase, deployTemplatesPath) {
        codebase.currentDeploymentVersion = getDeploymentVersion(context, codebase)
        def templateName = "${codebase.name}-install-${context.job.stageWithoutPrefixName}"

        if (codebase.need_database)
            script.sh("oc adm policy add-scc-to-user anyuid -z ${codebase.name} -n ${context.job.deployProject}")

        if (!checkTemplateExists(templateName, deployTemplatesPath)) {
            script.println("[JENKSIN][INFO] Trying to find out default template ${codebase.name}.yaml")
            templateName = codebase.name
            if (!checkTemplateExists(templateName, deployTemplatesPath))
                return
        }

        def imageName = codebase.inputIs ? codebase.inputIs : codebase.normalizedName
        context.platform.deployCodebase(
                context.job.deployProject,
                "${deployTemplatesPath}/${templateName}.yaml",
                "${context.job.metaProject}/${imageName}",
                codebase, context.job.dnsWildcard
        )
        checkDeployment(context, codebase, 'application')
    }

    def checkTemplateExists(templateName, deployTemplatesPath) {
        def templateYamlFile = new File("${deployTemplatesPath}/${templateName}.yaml")
        if (!templateYamlFile.exists()) {
            script.println("[JENKINS][WARNING] Template file which called ${templateName}.yaml doesn't exist in ${deployTemplatesPath} in the repository")
            return false
        }
        return true
    }

    def deployCodebase(version, name, context, codebase) {
        def codebaseDir = "${script.WORKSPACE}/${RandomStringUtils.random(10, true, true)}/${name}"
        def deployTemplatesPath = "${codebaseDir}/${context.job.deployTemplatesDirectory}"
        script.dir("${codebaseDir}") {
            if (!cloneProject(context, codebase)) {
                context.job.applicationsToPromote.remove(codebase.name)
                return
            }
            deployConfigMaps(codebaseDir, name, context)
            try {
                deployCodebaseTemplate(context, codebase, deployTemplatesPath)
            }
            catch (Exception ex) {
                script.unstable("[JENKINS][WARNING] Deployment of codebase ${name} has been failed. Reason - ${ex}.")
                script.currentBuild.setResult('UNSTABLE')
                context.job.applicationsToPromote.remove(codebase.name)
            }
        }
    }

    def getNElements(entities, max_apps) {
        def tempEntityList = entities.stream()
                .limit(max_apps.toInteger())
                .collect()
        entities.removeAll(tempEntityList)

        return tempEntityList
    }

    void run(context) {
        script.openshift.withCluster() {
            context.platform.createProjectIfNotExist(context.job.deployProject, context.job.edpName)
            def secretSelector = context.platform.getObjectList("secret")

            secretSelector.withEach { secret ->
                def sharedSecretName = secret.name().split('/')[1]
                def secretName = sharedSecretName.replace(context.job.sharedSecretsMask, '')
                if (sharedSecretName =~ /${context.job.sharedSecretsMask}/)
                    if (!context.platform.checkObjectExists('secrets', secretName))
                        context.platform.copySharedSecrets(sharedSecretName, secretName, context.job.deployProject)
            }

            if (context.job.buildUser == null || context.job.buildUser == "")
                context.job.buildUser = getBuildUserFromLog(context)

            if (context.job.buildUser != null && context.job.buildUser != "") {
                context.platform.createRoleBinding(context.job.buildUser, context.job.deployProject)
            }

            while (!context.job.servicesList.isEmpty()) {
                def parallelServices = [:]
                def tempServiceList = getNElements(context.job.servicesList, context.job.maxOfParallelDeployServices)

                tempServiceList.each() { service ->
                    if (!checkOpenshiftTemplateExists(context, service.name))
                        return

                    script.sh("oc adm policy add-scc-to-user anyuid -z ${service.name} -n ${context.job.deployProject}")

                    parallelServices["${service.name}"] = {
                        script.sh("oc -n ${context.job.edpName}-edp-cicd process ${service.name} " +
                                "-p SERVICE_VERSION=${service.version} " +
                                "-o json | oc -n ${context.job.deployProject} apply -f -")
                        checkDeployment(context, service, 'service')
                    }
                }

                script.parallel parallelServices
            }

            def deployCodebasesList = context.job.codebasesList.clone()
            while (!deployCodebasesList.isEmpty()) {
                def parallelCodebases = [:]
                def tempAppList = getNElements(deployCodebasesList, context.job.maxOfParallelDeployApps)

                tempAppList.each() { codebase ->
                    if ((codebase.version == "No deploy") || (codebase.version == "noImageExists")) {
                        script.println("[JENKINS][WARNING] Application ${codebase.name} deploy skipped")
                        return
                    }

                    if (codebase.version == "latest") {
                        codebase.version = codebase.latest
                        script.println("[JENKINS][DEBUG] Latest tag equals to ${codebase.latest} version")
                        if (!codebase.version)
                            return
                    }

                    if (codebase.version == "stable") {
                        codebase.version = codebase.stable
                        script.println("[JENKINS][DEBUG] Stable tag equals to ${codebase.stable} version")
                        if (!codebase.version)
                            return
                    }

                    if (!checkImageExists(context, codebase))
                        return

                    script.sh("oc adm policy add-scc-to-user anyuid -z ${codebase.name} -n ${context.job.deployProject}")
                    script.sh("oc adm policy add-role-to-user view system:serviceaccount:${context.job.deployProject}:${codebase.name} -n ${context.job.deployProject}")

                    parallelCodebases["${codebase.name}"] = {
                        deployCodebase(codebase.version, codebase.name, context, codebase)
                    }
                }
                script.parallel parallelCodebases
            }
        }
    }
}