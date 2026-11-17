param([string]$AndroidSdk = $env:ANDROID_HOME)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = Join-Path $root 'app/build/vision-bridge-tests'
$android = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$core = Join-Path $root 'core/build/libs/core.jar'
if (!(Test-Path $android) -or !(Test-Path $core)) { throw 'Install Android SDK 36 and build :core:jar before running these fixtures.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$classpath = $android, $core -join [IO.Path]::PathSeparator
$sources = @(
    (Join-Path $PSScriptRoot 'XposedHelpers.java'),
    (Join-Path $PSScriptRoot 'XC_MethodHook.java'),
    (Join-Path $PSScriptRoot 'TestImageBridge.java'),
    (Join-Path $root 'app/src/main/java/io/github/xiaoaiswitch/AssistantVersion.java'),
    (Join-Path $root 'app/src/main/java/io/github/xiaoaiswitch/ImageBridge.java')
)
& javac -encoding UTF-8 -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp ($output, $android, $core -join [IO.Path]::PathSeparator) io.github.xiaoaiswitch.TestImageBridge
exit $LASTEXITCODE
