param (
    [String] $RepoPath,
    [String] $Letter = "Z"
)

Push-Location $PSScriptRoot

mvn.exe compile exec:java `
    -Dexec.args="'$RepoPath' $Letter`: refs/heads/main"

Pop-Location

