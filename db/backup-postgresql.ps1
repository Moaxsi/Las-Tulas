param(
    [string]$BackupDirectory = (Join-Path $PSScriptRoot "..\backups"),
    [ValidateRange(1, 3650)]
    [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"

$databaseName = if ($env:PGDATABASE) { $env:PGDATABASE } else { "proyectomarcos" }
$databaseUser = if ($env:PGUSER) { $env:PGUSER } else { $env:SPRING_DATASOURCE_USERNAME }
$databaseHost = if ($env:PGHOST) { $env:PGHOST } else { "localhost" }
$databasePort = if ($env:PGPORT) { $env:PGPORT } else { "5432" }

if ($databaseName -notmatch '^[A-Za-z0-9_-]+$') {
    throw "PGDATABASE solo puede contener letras, numeros, guion y guion bajo."
}
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw "Define PGUSER o SPRING_DATASOURCE_USERNAME para ejecutar el respaldo."
}
if ([string]::IsNullOrWhiteSpace($env:PGPASSWORD) -and [string]::IsNullOrWhiteSpace($env:PGPASSFILE)) {
    throw "Configura PGPASSFILE (recomendado) o PGPASSWORD en el entorno del proceso."
}

$pgDump = Get-Command "pg_dump" -ErrorAction SilentlyContinue
$pgRestore = Get-Command "pg_restore" -ErrorAction SilentlyContinue
if (-not $pgDump -or -not $pgRestore) {
    throw "No se encontraron pg_dump y pg_restore en PATH. Instala las herramientas cliente de PostgreSQL."
}

$resolvedBackupDirectory = [System.IO.Path]::GetFullPath($BackupDirectory)
New-Item -ItemType Directory -Path $resolvedBackupDirectory -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
$backupFile = Join-Path $resolvedBackupDirectory "${databaseName}_${stamp}.dump"

& $pgDump.Source `
    "--host=$databaseHost" `
    "--port=$databasePort" `
    "--username=$databaseUser" `
    "--format=custom" `
    "--no-owner" `
    "--file=$backupFile" `
    $databaseName
if ($LASTEXITCODE -ne 0) {
    if (Test-Path -LiteralPath $backupFile) {
        Remove-Item -LiteralPath $backupFile -Force
    }
    throw "pg_dump finalizo con codigo $LASTEXITCODE."
}

& $pgRestore.Source --list $backupFile | Out-Null
if ($LASTEXITCODE -ne 0) {
    Remove-Item -LiteralPath $backupFile -Force -ErrorAction SilentlyContinue
    throw "El archivo generado no paso la verificacion de pg_restore."
}

$cutoff = (Get-Date).AddDays(-$RetentionDays)
$pattern = '^' + [regex]::Escape($databaseName) + '_\d{8}_\d{6}_\d{3}\.dump$'
Get-ChildItem -LiteralPath $resolvedBackupDirectory -File |
    Where-Object { $_.Name -match $pattern -and $_.LastWriteTime -lt $cutoff } |
    Remove-Item -Force

Write-Output "Respaldo verificado: $backupFile"
