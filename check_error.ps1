[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Get the latest run
$runs = Invoke-RestMethod -Uri 'https://api.github.com/repos/dongaicloud/bridge/actions/runs?per_page=1' -Headers @{'Accept'='application/vnd.github.v3+json'}
$runId = $runs.workflow_runs[0].id

Write-Host "Latest Run ID: $runId"

# Get jobs for this run
$jobs = Invoke-RestMethod -Uri "https://api.github.com/repos/dongaicloud/bridge/actions/runs/$runId/jobs" -Headers @{'Accept'='application/vnd.github.v3+json'}

foreach ($job in $jobs.jobs) {
    Write-Host "`nJob: $($job.name) - $($job.conclusion)"
    foreach ($step in $job.steps) {
        $status = if ($step.conclusion) { $step.conclusion } else { $step.status }
        Write-Host "  Step $($step.number): $($step.name) - $status"
    }
}
