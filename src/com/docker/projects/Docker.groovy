package com.docker.projects;

import groovy.transform.Field

@Field
def versionString = null

@Field
def imageId = null

def makeTask(nodeType, taskNames, extraEnv, Closure body=null) {
  return {
    wrappedNode(label: nodeType) {
      deleteDir()
      checkout(scm)
      echo "Pulling image ${imageId}"
      docker.image(imageId).pull()
      s3Fetch(destinationPath: "ci-metadata/", path: "ci-metadata/")
      s3Fetch(destinationPath: "bundles/", path: "bundles/")
      sh('''( [[ -f ci-metadata/executable-files.txt ]] && chmod -vv u+x $( cat ci-metadata/executable-files.txt ) && rm -rf ci-metadata ) ||:''')
      def envParts = [
        "KEEPBUNDLE=true",
        "SKIPBUNDLE=true",
      ]
      if (extraEnv) {
        try {
          envParts += extraEnv
        } catch (Exception exc) {
          echo "Couldn't glue together extra env, ignoring. ${extraEnv}; ${exc}"
        }
      }
      withEnv(envParts) {
        withChownWorkspace {
          sh("""
            export DOCKER_GRAPHDRIVER=\$( docker info | awk -F ': ' '\$1 == "Storage Driver" { print \$2; exit }' )
            docker run \\
              -i \\
              --rm \\
              --privileged \\
              -e KEEPBUNDLE \\
              -e SKIPBUNDLE \\
              -e TESTFLAGS \\
              -e DOCKER_BUILD_PKGS \\
              -v "\$(pwd)/bundles:/go/src/github.com/docker/docker/bundles" \\
              "${imageId}" \\
              hack/make.sh ${taskNames}
          """)
        }
        if (this.versionString == null) {
          sh("pushd bundles && ls | grep -v latest > ../version-string.txt && popd")
          this.versionString = readFile("version-string.txt").trim()
          sh("rm version-string.txt")
          echo "Got version string: ${this.versionString}"
        }
        if (body) { body() }
        echo("${taskNames} complete")
        sh("[[ -L bundles/latest ]] && rm bundles/latest")
        sh('''
          find bundles -type l -print0 | while read -d $'\0' f ; do
            echo "found link $f -> $(readlink "$f")"
            target="$( dirname "$f" )/$( readlink "$f" )"
            if [[ -e "$target" ]] ; then
              [[ -d "$target" ]] && CP_FLAGS="-R" && RM_FLAGS="-r"
              mv "$f" "$f.lnk" && cp $CP_FLAGS "$target" "$f" && rm $RM_FLAGS "$f.lnk"
            fi
            echo "Realized symlink for: $f -> $target"
          done
        ''')
        sh("mkdir -p ci-metadata && find bundles -type f -executable | tee ci-metadata/executable-files.txt")
        s3Archive(sourcePath: "ci-metadata/", path: "ci-metadata/")
        s3Archive(sourcePath: "bundles/", path: "bundles/")
      }
    }
  }
}

def go2xunit(task) {
  sh("cd bundles/${this.versionString}/${task} && docker run --rm bmangold/go2xunit < test-stdout.log > test.xml")
}

def junitArchive(task) {
  step([$class: 'JUnitResultArchiver', testResults: "bundles/${this.versionString}/${task}/test.xml", keepLongStdio: true])
}

def testTask(testName, label=null) {
  def needsXunit = testName != "test-docker-py"
  return this.makeTask(label ?: "docker", testName, []) {
    // Need to refresh timestamps else junit archiver will die.
    sh "find 'bundles/${this.versionString}/${testName}' -type f -print0 | xargs -0 touch"
    if (needsXunit) { this.go2xunit(testName) }
    this.junitArchive(testName)
  }
}

def integrationTask(label) {
  return this.testTask("test-integration-cli", label)
}

def packageTask(pkgTask, distro) {
  return this.makeTask("docker", "build-${pkgTask}", ["DOCKER_BUILD_PKGS=${distro}"])
}

def buildTask(buildTaskName) {
  return this.makeTask("docker", buildTaskName, [])
}

def validateTask(validateTaskName) {
  return this.makeTask("docker", validateTaskName, [])
}

return this
