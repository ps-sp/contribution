
import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Pattern

static void main(String[] args) {
    def startTime = System.nanoTime()
    def cliOptions = getCliOptions(args)
    def baseBranch = 'master'
    def patchBranch = cliOptions.patchBranch
    def localGitRepo = cliOptions.localGitRepo
    if (cliOptions != null && areValidCliOptions(cliOptions)) {
        if (hasUnstagedChanges(cliOptions.localGitRepo)) {
            def exMsg = "Error: git repository ${cfg.localGitRepo.path} has unstaged changes!"
            throw new IllegalStateException(exMsg)
        }
        if (cliOptions.baseBranch) {
            baseBranch = cliOptions.baseBranch
        }
        lineCount=1
        clearTargetDir()
        generateCheckstyleJar(localGitRepo, baseBranch)
        generateCheckstyleJar(localGitRepo, patchBranch)
        def fileNameFinder = new FileNameFinder()
        def baseJar = fileNameFinder.getFileNames("target", "*$baseBranch*.jar")
        def patchJar = fileNameFinder.getFileNames("target", "*$patchBranch*.jar")
        generateJavadocASTs(cliOptions, baseJar[0], patchJar[0])
        println("-----------------------------------------------------------------------")
        println("SUCCESS")
        println("-----------------------------------------------------------------------")
        def elapsedTime = System.nanoTime() - startTime
        elapsedTime/=1000000000
        elapsedTime/=60
        println("BUILD TIME:"+ elapsedTime + "m")
    }
    else {
        throw new IllegalArgumentException('Error: invalid command line arguments!')
    }
}

//excludes aren't supported
def getCliOptions(args) {
    def cliOptionsDescLineLength = 120
    def cli = new CliBuilder(usage:'groovy launch_antlr_regression.groovy [options]',
            header: 'options:', width: cliOptionsDescLineLength)
    cli.with {
        r(longOpt: 'localGitRepo', args: 1, required: true, argName: 'path',
                'Path to local git repository (required)')
        b(longOpt: 'baseBranch', args: 1, required: false, argName: 'branch_name',
                'Base branch name. Default is master (optional, default is master)')
        p(longOpt: 'patchBranch', args: 1, required: true, argName: 'branch_name',
                'Name of the patch branch in local git repository (required)')
        l(longOpt: 'listOfProjects', args: 1, required: true, argName: 'path',
                'Path to file which contains projects to test on (required)')
        i(longOpt: 'ignoreExceptions', required: false,
                'Whether to ignore exceptions (optional, default is false)')
        g(longOpt: 'ignoreExcludes', required: false,
                'Whether to ignore excludes specified in the list of projects (optional, default is false)')
    }
    return cli.parse(args)
}

def areValidCliOptions(cliOptions) {
    def valid = true
    def listOfProjectsFile = new File(cliOptions.listOfProjects)
    def localGitRepo = new File(cliOptions.localGitRepo)
    def patchBranch = cliOptions.patchBranch
    def baseBranch = cliOptions.baseBranch

    if (!listOfProjectsFile.exists()) {
        err.println "Error: file ${listOfProjectsFile.name} does not exist!"
        valid = false
    }
    else if (!isValidGitRepo(localGitRepo)) {
        err.println "Error: $localGitRepo is not a valid git repository!"
        valid = false
    }
    else if (!isExistingGitBranch(localGitRepo, patchBranch)) {
        err.println "Error: $patchBranch is not an exiting git branch!"
        valid = false
    }
    else if (baseBranch && !isExistingGitBranch(localGitRepo, baseBranch)) {
        err.println "Error: $baseBranch is not an existing git branch!"
        valid = false
    }
    else if ("$baseBranch" == "$patchBranch"
            || (!baseBranch && "${'master'}" == "$patchBranch")) {
        err.println("Error: Base branch and patch branch should be different")
        valid = false
    }

    return valid
}

def isValidGitRepo(gitRepoDir) {
    def valid = true
    if (gitRepoDir.exists() && gitRepoDir.isDirectory()) {
        def gitStatusCmd = "git status".execute(null, gitRepoDir)
        gitStatusCmd.waitFor()
        if (gitStatusCmd.exitValue() != 0) {
            err.println "Error: \'${gitRepoDir.getPath()}\' is not a git repository!"
            valid = false
        }
    }
    else {
        err.println "Error: \'${gitRepoDir.getPath()}\' does not exist or it is not a directory!"
        valid = false
    }
    return valid
}

def isExistingGitBranch(gitRepo, branchName) {
    def exist = true
    def gitRevParseCmd = "git rev-parse --verify $branchName".execute(null, gitRepo)
    gitRevParseCmd.waitFor()
    if (gitRevParseCmd.exitValue() != 0) {
        err.println "Error: git repository ${gitRepo.getPath()} does not have a branch with name \'$branchName\'!"
        exist = false
    }
    return exist
}

def hasUnstagedChanges(gitRepo) {
    def hasUnstagedChanges = true
    def gitStatusCmd = "git status".execute(null, new File(gitRepo))
    gitStatusCmd.waitFor()
    def gitStatusOutput = gitStatusCmd.text
    if (gitStatusOutput.contains("nothing to commit")) {
        hasUnstagedChanges = false
    }
    return hasUnstagedChanges
}

def clearTargetDir() {
    File targetDir = new File("target")
    deleteDir(targetDir)
    targetDir.mkdir()
}

def generateCheckstyleJar(repoLoc, branch) {
    packageCheckstyle(repoLoc, branch)
    def jarPath = new FileNameFinder().getFileNames("$repoLoc/target", "*all.jar")
    def jarFile = new File(jarPath[0])
    Files.copy(jarFile.toPath(),new File("target/$branch"+".jar").toPath())
}

def packageCheckstyle(repoLoc, branch) {
    def cmdCheckoutBranch = "git checkout $branch"
    executeCmd(cmdCheckoutBranch, new File(repoLoc))
    def cmdPackCheckstyle = "mvn clean package -Passembly"
    executeCmd(cmdPackCheckstyle, new File(repoLoc))
}

def generateJavadocASTs(cliOptions, baseJar, patchJar) {

    def targetDir = 'target'
    def srcDir = "src/main/java"
    def reposDir = 'repositories'
    def reportsDir = "reports"
    createWorkDirsIfNotExist(srcDir, reposDir, reportsDir)

    def REPO_NAME_PARAM_NO = 0
    def REPO_TYPE_PARAM_NO = 1
    def REPO_URL_PARAM_NO = 2
    def REPO_COMMIT_ID_PARAM_NO = 3
    def REPO_EXCLUDES_PARAM_NO = 4
    def FULL_PARAM_LIST_SIZE = 5

    def ignoreExceptions = cliOptions.ignoreExceptions
    def listOfProjectsFile = new File(cliOptions.listOfProjects)
    def projects = listOfProjectsFile.readLines()

    projects.each {
        project ->
            if (!project.startsWith('#') && !project.isEmpty()) {
                def params = project.split('\\|', -1)
                if (params.length < FULL_PARAM_LIST_SIZE) {
                    throw new InvalidPropertiesFormatException("Error: line '$project' in file '$listOfProjectsFile.name' should have $FULL_PARAM_LIST_SIZE pipe-delimeted sections!")
                }

                def repoName = params[REPO_NAME_PARAM_NO]
                def repoType = params[REPO_TYPE_PARAM_NO]
                def repoUrl = params[REPO_URL_PARAM_NO]
                def commitId = params[REPO_COMMIT_ID_PARAM_NO]
                reportsDir = reportsDir + File.separator + repoName

                cloneRepository(repoName, repoType, repoUrl, commitId, reposDir)
                deleteDir(srcDir)
                copyDir("$reposDir/$repoName", "$srcDir/$repoName")
                deleteDir("$reportsDir/$repoName")
                Thread.start {
                    printJavadocASTsForFilesInDir(srcDir, baseJar, reportsDir)
                }
                printJavadocASTsForFilesInDir(srcDir, patchJar, reportsDir)
                //postProcessCheckstyleReport(targetDir)
                deleteDir("$srcDir/$repoName")
            }
    }

    // restore empty_file to make src directory tracked by git
    new File("$srcDir/empty_file").createNewFile()
}

def createWorkDirsIfNotExist(srcDirPath, repoDirPath, reportsDirPath) {
    def srcDir = new File(srcDirPath)
    if (!srcDir.exists()) {
        srcDir.mkdirs()
    }
    def repoDir = new File(repoDirPath)
    if (!repoDir.exists()) {
        repoDir.mkdir()
    }
    def reportsDir = new File(reportsDirPath)
    if (!reportsDir.exists()) {
        reportsDir.mkdir()
    }
}

def cloneRepository(repoName, repoType, repoUrl, commitId, srcDir) {
    def srcDestinationDir = "$srcDir/$repoName"
    if (!Files.exists(Paths.get(srcDestinationDir))) {
        def cloneCmd = getCloneCmd(repoType, repoUrl, srcDestinationDir)
        println "Cloning $repoType repository '$repoName' to $srcDestinationDir folder ..."
        executeCmdWithRetry(cloneCmd)
        println "Cloning $repoType repository '$repoName' - completed\n"
    }

    if (commitId && commitId != '') {
        def lastCommitSha = getLastCommitSha(repoType, srcDestinationDir)
        def commitIdSha = getCommitSha(commitId, repoType, srcDestinationDir)
        if (lastCommitSha != commitIdSha) {
            def resetCmd = getResetCmd(repoType, commitId)
            println "Reseting $repoType sources to commit '$commitId'"
            executeCmd(resetCmd, new File("$srcDestinationDir"))
        }
    }
    println "$repoName is synchronized"
}

def getCloneCmd(repoType, repoUrl, srcDestinationDir) {
    def cloneCmd = ''
    switch (repoType) {
        case 'git':
            cloneCmd = "git clone $repoUrl $srcDestinationDir"
            break
        case 'hg':
            cloneCmd = "hg clone $repoUrl $srcDestinationDir"
            break
        default:
            throw new IllegalArgumentException("Error! Unknown $repoType repository.")
    }
    return cloneCmd
}

def getLastCommitSha(repoType, srcDestinationDir) {
    def cmd = ''
    switch (repoType) {
        case 'git':
            cmd = "git rev-parse HEAD"
            break
        case 'hg':
            cmd = "hg id -i"
            break
        default:
            throw new IllegalArgumentException("Error! Unknown $repoType repository.")
    }
    def sha = cmd.execute(null, new File("$srcDestinationDir")).text
    // cmd output contains new line character which should be removed
    return sha.replace('\n', '')
}

def getCommitSha(commitId, repoType, srcDestinationDir) {
    def cmd = ''
    switch (repoType) {
        case 'git':
            cmd = "git rev-parse $commitId"
            break
        case 'hg':
            cmd = "hg identify --id $commitId"
            break
        default:
            throw new IllegalArgumentException("Error! Unknown $repoType repository.")
    }
    def sha = cmd.execute(null, new File("$srcDestinationDir")).text
    // cmd output contains new line character which should be removed
    return sha.replace('\n', '')
}

def getResetCmd(repoType, commitId) {
    def resetCmd = ''
    switch (repoType) {
        case 'git':
            resetCmd = "git reset --hard $commitId"
            break
        case 'hg':
            resetCmd = "hg up $commitId"
            break
        default:
            throw new IllegalArgumentException("Error! Unknown $repoType repository.")
    }
}

def copyDir(source, destination) {
    new AntBuilder().copy(todir: destination) {
        fileset(dir: source)
    }
}

def moveDir(source, destination) {
    new AntBuilder().move(todir: destination) {
        fileset(dir: source)
    }
}

def deleteDir(dir) {
    new AntBuilder().delete(dir: dir, failonerror: false)
}

def printJavadocASTsForFilesInDir(dir, jar, reportsDir) {
    def src = new File(dir)
    def fileNameFinder = new FileNameFinder()
    def files = fileNameFinder.getFileNames(src.getCanonicalPath(), "**/*.java")
    reportsDir = reportsDir + jar.substring(jar.lastIndexOf(File.separator), jar.lastIndexOf('.'))
    for (String filepath : files) {
        def outputFileRelativePath = src.toURI().relativize(new File(filepath).toURI()).toString()
        outputFileRelativePath = outputFileRelativePath
                .substring(0,outputFileRelativePath.lastIndexOf('.')).concat(".tree")
        def outputFile = new File("$reportsDir/$outputFileRelativePath")
        if (outputFile.exists()) {
            outputFile.delete()
            outputFile.createNewFile()
        } else {
            outputFile.getParentFile().mkdirs()
            outputFile.createNewFile()
        }
        def cmd = "java -Xmx256M -jar $jar -j $filepath > $outputFile 2>&1"
        executeCmdWithRedirectedOutput(cmd, outputFile)
    }

}

def postProcessCheckstyleReport(targetDir) {
    def siteDir = "$targetDir/site"
    println 'linking report to index.html'
    new File("$siteDir/index.html").renameTo "$siteDir/_index.html"
    Files.createLink(Paths.get("$siteDir/index.html"), Paths.get("$siteDir/checkstyle.html"))

    removeNonReferencedXrefFiles(siteDir)
    removeEmptyDirectories(new File("$siteDir/xref"))

    new AntBuilder().replace(
            file: "$targetDir/checkstyle-result.xml",
            token: "checkstyle-tester/src/main/java",
            value: "checkstyle-tester/repositories"
    )
}

def removeNonReferencedXrefFiles(siteDir) {
    println 'Removing non refernced xref files in report ...'

    def linesFromIndexHtml = Files.readAllLines(Paths.get("$siteDir/index.html"))
    def filesReferencedInReport = getFilesReferencedInReport(linesFromIndexHtml)

    Paths.get("$siteDir/xref").toFile().eachFileRecurse {
        fileObj ->
            def path = fileObj.getPath()
            path = path.substring(path.indexOf("xref"))
            def fileName = fileObj.getName()
            if (fileObj.isFile()
                    && !filesReferencedInReport.contains(path)
                    && 'stylesheet.css' != fileName
                    && 'allclasses-frame.html' != fileName
                    && 'index.html' != fileName
                    && 'overview-frame.html' != fileName
                    && 'overview-summary.html' != fileName) {
                fileObj.delete()
            }
    }
}

def getFilesReferencedInReport(linesFromIndexHtml) {
    def xrefStartIdx = 2
    def pattern = Pattern.compile('\\./xref/[^<>]+\\.html')
    def referencedFiles = new HashSet<String>()
    linesFromIndexHtml.each {
        line ->
            def matcher = pattern.matcher(line)
            if (matcher.find()) {
                referencedFiles.addAll(matcher.collect { it.substring(xrefStartIdx) })
            }
    }
    return referencedFiles
}

def removeEmptyDirectories(file) {
    def contents = file.listFiles()
    if (contents != null) {
        for (File f : contents) {
            removeEmptyDirectories(f)
        }
    }
    if (file.isDirectory() && file.listFiles().length == 0) {
        file.delete()
    }
}

def executeCmd(cmd, dir =  new File("").getAbsoluteFile()) {
    def osSpecificCmd = getOsSpecificCmd(cmd)
    def proc = osSpecificCmd.execute(null, dir)
    proc.consumeProcessOutput(System.out, System.err)
    proc.waitFor()
    if (proc.exitValue() != 0) {
        throw new GroovyRuntimeException("Error: ${proc.err.text}!")
    }
}

def executeCmdWithRedirectedOutput(cmd, outputFile,
                                   dir =  new File("").getAbsoluteFile()) {
    def osSpecificCmd = getOsSpecificCmd(cmd)
    def proc = osSpecificCmd.execute()
    proc.waitForOrKill(10000)
    if (proc.exitValue() != 0) {
        System.err.println(lineCount++ + " :Error printing "
                + outputFile + "\n[ERROR] ${proc.err.text}")
    } else {
        println(lineCount++ + " :Printed " + outputFile)
    }
}

def executeCmdWithRetry(cmd, dir =  new File("").getAbsoluteFile(), retry = 5) {
    def osSpecificCmd = getOsSpecificCmd(cmd)
    def left = retry
    while (true) {
        def proc = osSpecificCmd.execute(null, dir)
        proc.consumeProcessOutput(System.out, System.err)
        proc.waitFor()
        left--
        if (proc.exitValue() != 0) {
            if (left <= 0) {
                throw new GroovyRuntimeException("Error: ${proc.err.text}!")
            }
            else {
                Thread.sleep(15000)
            }
        }
        else {
            break
        }
    }
}

def getOsSpecificCmd(cmd) {
    def osSpecificCmd
    if (System.properties['os.name'].toLowerCase().contains('windows')) {
        osSpecificCmd = "cmd /c $cmd"
    }
    else {
        osSpecificCmd = cmd
    }
}
