param (
    [String] $RepoPath,
    [String] $Letter = "Z"
)

Push-Location $PSScriptRoot

& "$PSScriptRoot\.jdk17\env.ps1"
& "$PSScriptRoot\.maven\env.ps1"
& "$PSScriptRoot\.winfsp\env.ps1"

mvn.cmd compile exec:java `
    "-Dexec.args='$RepoPath' $Letter`: refs/heads/main"

Pop-Location

