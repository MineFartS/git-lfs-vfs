param (
    [String] $RepoPath,
    [String] $Letter = "Z"
)

Push-Location $PSScriptRoot

& "$PSScriptRoot\.jdk17\env.ps1"

mvn.exe compile exec:java `
    -Dexec.args="'$RepoPath' $Letter`: refs/heads/main"

Pop-Location

