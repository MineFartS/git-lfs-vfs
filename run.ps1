Push-Location $PSScriptRoot

& "$PSScriptRoot\.jdk17\env.ps1"
& "$PSScriptRoot\.maven\env.ps1"
& "$PSScriptRoot\.winfsp\env.ps1"

mvn.cmd compile `
    "dependency:build-classpath" `
    "-Dmdep.outputFile=target\cp.txt"

java.exe `
    "-Djnrfuse.winfsp.path=$PSScriptRoot\.winfsp\bin\winfsp-x64.dll" `
    -cp ("target\classes;" + (Get-Content "target\cp.txt" -Raw)) `
    'vfs.MainApp' `
    @args

Pop-Location