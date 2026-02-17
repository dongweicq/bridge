[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$runs = Invoke-RestMethod -Uri 'https://api.github.com/repos/dongaicloud/bridge/actions/runs?per_page=3' -Headers @{'Accept'='application/vnd.github.v3+json'}

foreach ($run in $runs.workflow_runs) {
    Write-Host ("Run #" + $run.run_number + ": " + $run.status + " - " + $run.conclusion + " (" + $run.display_title + ")")
}
