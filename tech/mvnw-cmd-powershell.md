# `mvnw.cmd`: Windows batch and PowerShell in one file

## What it is

`mvnw.cmd` does for Windows what `mvnw` does for macOS and Linux (`mvnw-script.md`): it makes sure the pinned Maven is on the machine and then starts it. It is written in two languages at once.

- **Batch** is the old scripting language of the Windows command prompt (`cmd.exe`). A **command prompt** is the text window where you type commands. A **batch file** (ending in `.cmd` or `.bat`) is a list of such commands that Windows runs one after another.
- **PowerShell** is Windows' newer, more powerful scripting language. It works with named objects instead of plain text and has commands for downloading files, unpacking archives and computing checksums.

One file can be valid in both languages (a **polyglot**). The first part is batch code hidden inside a PowerShell comment; the second part is PowerShell. Batch starts PowerShell on the same file; PowerShell skips the batch part and runs the rest.

This repository never runs `mvnw.cmd` (the commands in the `Makefile` are for macOS and Linux and the CI runs on Linux). It is part of the wrapper that was copied in, so this note reads it completely. I could not run it on this Mac, so what is said about Windows behavior comes from reading the script and the Windows and PowerShell documentation, not from a Windows run.

## Why this repository uses it

A reader on Windows who opens a command prompt in the project folder can type `mvnw.cmd verify` and get the same pinned Maven (3.9.16) without installing Maven. The file also keeps the repository consistent with what the wrapper generator produces: a wrapper with only one of the two scripts would surprise people.

## Where it appears

| Path | What it is |
| --- | --- |
| `mvnw.cmd` | The file. Its lines end with the two characters carriage return and line feed (CRLF), the Windows line ending (`file mvnw.cmd` says "with CRLF line terminators"). |
| `.mvn/wrapper/maven-wrapper.properties` | The settings it reads (`maven-wrapper.md`). |
| `%USERPROFILE%\.m2\wrapper\dists\...` | Where it keeps Maven on Windows. `$HOME/.m2` in PowerShell is the same folder as `~/.m2` elsewhere. |

## How it works underneath

You need these ideas first.

- **Environment variable.** A named piece of text a process inherits. In batch you read one with `%NAME%` and set one with `SET NAME=value`. In PowerShell you read one with `$env:NAME`.
- **`%0`, `%~dp0`, `%~f0`, `%~nx0`, `%*`.** Inside a batch file `%0` is the name used to start the file. Adding `~` and letters picks parts: `%~dp0` is the **d**rive and **p**ath (the folder, ending in a backslash), `%~f0` the **f**ull path, `%~nx0` the **n**ame and e**x**tension (`mvnw.cmd`). `%*` stands for all the arguments you typed after the name.
- **`@`.** A batch line that starts with `@` is not printed before it runs. Without it Windows would echo each command.
- **`REM`.** A comment in batch (`@REM` is the quiet form).
- **`IF`, `FOR /F`, `GOTO :EOF`.** `IF a==b (...)` runs the part in brackets when the two texts are equal. `FOR /F "options" %%A IN (`command`) DO ...` runs the command in the back-quotes and processes each line of its output; `tokens=1*` and `delims==` split each line at the first `=` into `%%A` (before it) and `%%B` (everything after it). `GOTO :EOF` jumps to the end of the file, which ends the script.
- **Starting another batch file without `CALL`.** If a batch file starts another batch file by its name alone (without the word `CALL`), control never comes back. That is how the batch part ends: it hands over to Maven's `mvn.cmd` and nothing after that line runs unless Maven could not be started.
- **PowerShell comments.** `<#` starts a block comment and `#>` ends it; `#` starts a comment to the end of the line. Anything in a block comment is ignored by PowerShell, including whole lines of batch code.
- **PowerShell basics used below.** A **variable** starts with `$`. A **cmdlet** is a built-in command named `Verb-Noun` (for example `Get-Content`, `Test-Path`, `New-Item`). A **pipe** `|` sends the output of the left command into the right one. `$_` is "the current item" inside `ForEach-Object`. `| Out-Null` throws the output away. `-eq`, `-ne`, `-and`, `-or`, `-not` are the comparison words. `-replace` replaces text using a **regular expression** (a pattern language: `^` start of text, `$` end of text, `.*` any characters, `[^.]*` any characters that are not a dot, `\.` a literal dot). `try { } catch { } finally { }` runs the first block, and the second only if the first failed, and the third in every case. `trap { }` runs its block whenever an error stops the script.

How the two halves cooperate:

1. `cmd.exe` runs the batch lines (2 to 45). Line 35 starts PowerShell and tells it to read **this same file** as PowerShell code.
2. PowerShell sees `<#` on line 1 and ignores everything up to the `#>` on line 46. It runs lines 48 to 189, which do the real work.
3. The PowerShell part **prints text** instead of starting Maven itself. The one line that matters is `MVN_CMD=<path to mvn.cmd>`.
4. Back in batch, the loop on lines 35 to 37 reads that printed text: the line starting with `MVN_CMD` is stored, any other line is printed to you.
5. Line 43 runs the stored path with your arguments.

## Walkthrough of this repository's use

### The batch part (lines 1 to 46)

**Line 1**

```bat
<# : batch portion
```

PowerShell reads `<#` as the start of a block comment, so everything up to line 46 is invisible to it. For `cmd.exe` this first line is written so that it has no visible effect (the script's authors designed it as the opening of the hybrid file; I could not check how `cmd.exe` treats it on a real Windows machine).

**Lines 2 to 19**

```bat
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------
```

The Apache licence notice, as batch comments (`@REM`). It is the same text as in `mvnw`: Apache License 2.0, which lets anyone use and change the file as long as the notice stays.

**Lines 21 to 28**

```bat
@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.4
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM ----------------------------------------------------------------------------
```

A comment naming the script (version 3.3.4) and the three environment variables it understands: `MVNW_REPOURL` (a different place to download from), `MVNW_USERNAME` and `MVNW_PASSWORD` (credentials), and `MVNW_VERBOSE` (`true` prints progress messages). Unlike `mvnw`, this script has no `JAVA_HOME` variable and no `debug` level.

**Lines 30 to 34**

```bat
@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
```

- Line 30: if `__MVNW_ARG0_NAME__` is empty, set it to `%~nx0`, the script's own file name (`mvnw.cmd`). The PowerShell part uses it to decide the command name (`mvn.cmd`).
- Lines 31 and 32: make sure two helper variables start empty. Names with double underscores are chosen so that they cannot collide with anything of yours.
- Line 33: save the current `PSModulePath` (the list of folders where PowerShell looks for its add-on modules) into another variable.
- Line 34: empty it for the moment, so the Windows PowerShell started on line 35 uses only its own built-in modules and is not confused by modules meant for a different PowerShell on the same machine.

**Lines 35 to 37**

```bat
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir='%~dp0'; $script='%__MVNW_ARG0_NAME__%'; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw '%~f0'))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
```

This is the core of the batch part. Take it apart:

- `FOR /F "usebackq tokens=1* delims==" %%A IN (`...`) DO @( ... )` runs the command between the back-quotes and processes its output line by line. `usebackq` is what allows the back-quotes. `tokens=1*` with `delims==` splits each output line at the first `=`.
- The command is `powershell -noprofile "& { ... }"`. `-noprofile` starts PowerShell without running the user's own start-up script, so the behavior is the same on every machine. Inside the double quotes, between the braces: `$scriptDir='%~dp0'` sets a PowerShell variable to the folder of this script; `$script='%__MVNW_ARG0_NAME__%'` sets another to the script's file name; `Get-Content -Raw '%~f0'` reads this entire file as one piece of text; `[Scriptblock]::Create(...)` turns that text into runnable PowerShell code; `icm -ScriptBlock ... -NoNewScope` (`icm` is short for `Invoke-Command`) runs it, in the current scope so the two variables are visible to it.
- Line 36 handles each output line: if the part before `=` is `MVN_CMD`, store the part after it in `__MVNW_CMD__`; otherwise, if there is nothing after `=`, print the line (`echo %%A`); otherwise print it with its `=` put back (`echo %%A=%%B`). That is how PowerShell's messages reach you while the one special line is captured.
- Line 37 closes the brackets.

**Lines 38 to 42**

```bat
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%
@SET __MVNW_PSMODULEP_SAVE=
@SET __MVNW_ARG0_NAME__=
@SET MVNW_USERNAME=
@SET MVNW_PASSWORD=
```

Clean up: restore `PSModulePath`, then empty the helper variables, and also empty `MVNW_USERNAME` and `MVNW_PASSWORD` so the credentials are not passed on to Maven.

**Lines 43 to 46**

```bat
@IF NOT "%__MVNW_CMD__%"=="" ("%__MVNW_CMD__%" %*)
@echo Cannot start maven from wrapper >&2 && exit /b 1
@GOTO :EOF
: end batch / begin powershell #>
```

- Line 43: if a Maven command was found, run it with all your arguments (`%*`). Because it is a batch file started without `CALL`, control does not come back (see above).
- Line 44: only reached when the line above did not run (no command was found): print a message to the error channel (`>&2`) and exit with status 1 (`exit /b 1` leaves the batch file with that number).
- Line 45: `GOTO :EOF`, jump to the end of the file. The batch part never reads the PowerShell lines below.
- Line 46: `: end batch / begin powershell #>`. The `#>` closes the PowerShell block comment that began on line 1. From here on, PowerShell reads real code.

### The PowerShell part (lines 48 to 189)

**Lines 48 to 51: strictness and verbose switch**

```powershell
$ErrorActionPreference = "Stop"
if ($env:MVNW_VERBOSE -eq "true") {
  $VerbosePreference = "Continue"
}
```

`$ErrorActionPreference = "Stop"` makes any error stop the script (the default would print it and continue). If `MVNW_VERBOSE` is the text `true`, `$VerbosePreference = "Continue"` makes `Write-Verbose` messages visible.

**Lines 53 to 57: reading the settings file**

```powershell
# calculate distributionUrl, requires .mvn/wrapper/maven-wrapper.properties
$distributionUrl = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}
```

`Get-Content -Raw "..."` reads the whole `maven-wrapper.properties` as text; `ConvertFrom-StringData` turns `key=value` lines into a table (a **hashtable**) so `.distributionUrl` picks one value. `$scriptDir` came from line 35. If the value is missing, `Write-Error` stops the script with that message (stopping because of line 48).

**Lines 59 to 71: choosing the command**

```powershell
switch -wildcard -casesensitive ( $($distributionUrl -replace '^.*/','') ) {
  "maven-mvnd-*" {
    $USE_MVND = $true
    $distributionUrl = $distributionUrl -replace '-bin\.[^.]*$',"-windows-amd64.zip"
    $MVN_CMD = "mvnd.cmd"
    break
  }
  default {
    $USE_MVND = $false
    $MVN_CMD = $script -replace '^mvnw','mvn'
    break
  }
}
```

`$distributionUrl -replace '^.*/',''` removes everything up to the last slash, leaving the file name (`apache-maven-3.9.16-bin.zip`). `switch -wildcard -casesensitive` compares that name with patterns. For the Maven Daemon (`maven-mvnd-*`) it sets `$USE_MVND`, rewrites the address to a Windows zip and chooses `mvnd.cmd`. **This repository takes `default`**: `$USE_MVND` is false and `$MVN_CMD = $script -replace '^mvnw','mvn'`, which turns `mvnw.cmd` into `mvn.cmd`. `break` leaves the `switch`.

**Lines 73 to 80: mirror and names**

```powershell
# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = if ($USE_MVND -eq $False) { "/org/apache/maven/" } else { "/maven/mvnd/" }
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace "^.*$MVNW_REPO_PATTERN",'')"
}
$distributionUrlName = $distributionUrl -replace '^.*/',''
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$','' -replace '-bin$',''
```

If `MVNW_REPOURL` is set, the address is rebuilt on the mirror's base. Line 79 keeps the file name; line 80 removes the extension (`'\.[^.]*$'` is "a dot followed by non-dot characters at the end", i.e. `.zip`) and then the ending `-bin`, leaving `apache-maven-3.9.16`.

**Lines 82 to 96: the cache folder**

```powershell
$MAVEN_M2_PATH = "$HOME/.m2"
if ($env:MAVEN_USER_HOME) {
  $MAVEN_M2_PATH = "$env:MAVEN_USER_HOME"
}

if (-not (Test-Path -Path $MAVEN_M2_PATH)) {
    New-Item -Path $MAVEN_M2_PATH -ItemType Directory | Out-Null
}

$MAVEN_WRAPPER_DISTS = $null
if ((Get-Item $MAVEN_M2_PATH).Target[0] -eq $null) {
  $MAVEN_WRAPPER_DISTS = "$MAVEN_M2_PATH/wrapper/dists"
} else {
  $MAVEN_WRAPPER_DISTS = (Get-Item $MAVEN_M2_PATH).Target[0] + "/wrapper/dists"
}
```

`$HOME/.m2` is the default Maven user folder; `MAVEN_USER_HOME` overrides it (lines 82 to 85). Lines 87 to 89 create it if missing (`Test-Path` asks whether a path exists; `New-Item ... -ItemType Directory` creates a folder; `| Out-Null` hides the output). Lines 91 to 96 handle the case where `.m2` is a **symbolic link** (a shortcut-like entry that points at another folder): `(Get-Item $MAVEN_M2_PATH).Target[0]` is where the link points, and it is empty for a normal folder. Either way `$MAVEN_WRAPPER_DISTS` ends in `/wrapper/dists`.

**Lines 98 to 100: the folder for this version**

```powershell
$MAVEN_HOME_PARENT = "$MAVEN_WRAPPER_DISTS/$distributionUrlNameMain"
$MAVEN_HOME_NAME = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"
```

`$MAVEN_HOME_PARENT` is `.../wrapper/dists/apache-maven-3.9.16`. `$MAVEN_HOME_NAME` is a **SHA-256 hash** of the address text, written as hexadecimal (`ToString("x2")` prints each byte as two hex digits; `-join ''` glues them). Note the difference from `mvnw`, which uses a short Java-style hash: the folder names differ between Windows and macOS or Linux, which is harmless because each machine has its own cache.

**Lines 102 to 110: use it if it is there**

```powershell
if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  exit $?
}

if (! $distributionUrlNameMain -or ($distributionUrlName -eq $distributionUrlNameMain)) {
  Write-Error "distributionUrl is not valid, must end with *-bin.zip, but found $distributionUrl"
}
```

If the folder exists, print `MVN_CMD=<path>` (the line the batch loop is waiting for) and exit; `Write-Verbose` prints a progress message only in verbose mode. The batch part reads the printed text, not the exit number. Lines 108 to 110 check the address is valid: it must end in `-bin.zip`, which means the cleaned name must differ from the original.

**Lines 112 to 123: a temporary folder**

```powershell
# prepare tmp dir
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

New-Item -Itemtype Directory -Path "$MAVEN_HOME_PARENT" -Force | Out-Null
```

`New-TemporaryFile` creates an empty file with a unique name; a folder with the same name plus `.dir` is created next to it; then the empty file is deleted. The `trap { ... }` block is a clean-up that runs if anything fails: it removes the temporary folder if it exists (`try`/`catch` prints a warning if removal fails). Line 123 creates the parent folder of the final Maven folder (`-Force` makes it succeed even if it exists).

**Lines 125 to 135: the download**

```powershell
# Download and Install Apache Maven
Write-Verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
Write-Verbose "Downloading from: $distributionUrl"
Write-Verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"

$webclient = New-Object System.Net.WebClient
if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
  $webclient.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, "$TMP_DOWNLOAD_DIR/$distributionUrlName") | Out-Null
```

`Write-Verbose` lines announce the plan. `New-Object System.Net.WebClient` creates a small .NET object that can download a file. If both user name and password are set, they are given to it as credentials. `[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12` says to use TLS 1.2, the secure-connection protocol that Maven Central requires. `DownloadFile(address, path)` saves the zip into the temporary folder.

**Lines 137 to 147: optional checksum**

```powershell
# If specified, validate the SHA-256 sum of the Maven distribution zip file
$distributionSha256Sum = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionSha256Sum
if ($distributionSha256Sum) {
  if ($USE_MVND) {
    Write-Error "Checksum validation is not supported for maven-mvnd. `nPlease disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties."
  }
  Import-Module $PSHOME\Modules\Microsoft.PowerShell.Utility -Function Get-FileHash
  if ((Get-FileHash "$TMP_DOWNLOAD_DIR/$distributionUrlName" -Algorithm SHA256).Hash.ToLower() -ne $distributionSha256Sum) {
    Write-Error "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
}
```

Read `distributionSha256Sum` from the settings file. This repository does not set one, so the `if` does not run. If it were set: for mvnd it is an error; otherwise `Get-FileHash ... -Algorithm SHA256` computes the file's fingerprint, `.Hash.ToLower()` turns it to lowercase, and a difference stops the script with an error saying the distribution might be compromised.

**Lines 149 and 150: unpack**

```powershell
# unzip and move
Expand-Archive "$TMP_DOWNLOAD_DIR/$distributionUrlName" -DestinationPath "$TMP_DOWNLOAD_DIR" | Out-Null
```

`Expand-Archive` unzips the file into the temporary folder.

**Lines 152 to 174: find the unpacked folder**

```powershell
# Find the actual extracted directory name (handles snapshots where filename != directory name)
$actualDistributionDir = ""

# First try the expected directory name (for regular distributions)
$expectedPath = Join-Path "$TMP_DOWNLOAD_DIR" "$distributionUrlNameMain"
$expectedMvnPath = Join-Path "$expectedPath" "bin/$MVN_CMD"
if ((Test-Path -Path $expectedPath -PathType Container) -and (Test-Path -Path $expectedMvnPath -PathType Leaf)) {
  $actualDistributionDir = $distributionUrlNameMain
}

# If not found, search for any directory with the Maven executable (for snapshots)
if (!$actualDistributionDir) {
  Get-ChildItem -Path "$TMP_DOWNLOAD_DIR" -Directory | ForEach-Object {
    $testPath = Join-Path $_.FullName "bin/$MVN_CMD"
    if (Test-Path -Path $testPath -PathType Leaf) {
      $actualDistributionDir = $_.Name
    }
  }
}

if (!$actualDistributionDir) {
  Write-Error "Could not find Maven distribution directory in extracted archive"
}
```

Same job as in `mvnw`. First try the folder named like the file (`Join-Path` joins path parts) and require `bin/mvn.cmd` inside (`-PathType Container` means a folder, `-PathType Leaf` a file). If that fails, `Get-ChildItem -Directory` lists the folders in the temporary folder and `ForEach-Object` checks each for `bin/mvn.cmd`; `$_.Name` is the folder's name. If nothing is found, an error stops the script.

**Lines 176 to 189: install and report**

```powershell
Write-Verbose "Found extracted Maven distribution directory: $actualDistributionDir"
Rename-Item -Path "$TMP_DOWNLOAD_DIR/$actualDistributionDir" -NewName $MAVEN_HOME_NAME | Out-Null
try {
  Move-Item -Path "$TMP_DOWNLOAD_DIR/$MAVEN_HOME_NAME" -Destination $MAVEN_HOME_PARENT | Out-Null
} catch {
  if (! (Test-Path -Path "$MAVEN_HOME" -PathType Container)) {
    Write-Error "fail to move MAVEN_HOME"
  }
} finally {
  try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
  catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
}

Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
```

`Rename-Item` renames the unpacked folder to the hash name; `Move-Item` moves it under `$MAVEN_HOME_PARENT`. If the move fails but the destination exists (another run did it first), that is fine; if it does not exist, `Write-Error` stops. The `finally` block always deletes the temporary folder. The last line prints `MVN_CMD=<path>/bin/mvn.cmd`, which the batch loop stores on line 36 and runs on line 43.

## Try it yourself

On macOS or Linux you can read the file and check its shape; running it needs Windows.

1. Count the lines and see the line endings: `wc -l mvnw.cmd` and `file mvnw.cmd`.
2. See where the batch part ends and PowerShell begins: `grep -n 'begin powershell' mvnw.cmd`.
3. See the first line: `head -1 mvnw.cmd`.
4. On Windows, open a command prompt in the project folder and run `mvnw.cmd -v`. It prints the Maven version (3.9.16).
5. On Windows, run `set MVNW_VERBOSE=true` and then `mvnw.cmd -v` to see the progress messages the PowerShell part prints.

## Mistakes people make

- **Running `mvnw.cmd` in Git Bash or WSL and expecting it to work like `./mvnw`.** In those shells use `./mvnw`; `mvnw.cmd` is for the Windows command prompt and PowerShell.
- **Changing the line endings.** The batch half needs CRLF endings; a tool that converts them to LF can break the file.
- **Editing the file by hand.** It is generated by the wrapper plugin; changes belong in a regenerated copy.
- **Expecting the folder name to match `mvnw`.** The hash method differs (SHA-256 here, a Java-style hash there), so the two scripts never share a cache folder.
- **Forgetting that PowerShell's `-replace` takes a pattern, not plain text.** A dot in the pattern matches any character; the script escapes it as `\.` where it means a dot.

## Check yourself

1. *Why can one file be both batch and PowerShell?* The batch code sits inside a PowerShell block comment (`<#` on line 1, `#>` on line 46), and the batch part ends with `GOTO :EOF` before the PowerShell code.
2. *How does the PowerShell part tell the batch part which Maven to run?* It prints a line `MVN_CMD=<path>`; the batch loop on lines 35 to 37 reads it.
3. *What does `%*` mean on line 43?* All the arguments typed after the script name.
4. *Which line decides the command name `mvn.cmd`?* Line 68: `$MVN_CMD = $script -replace '^mvnw','mvn'`.
5. *Which settings does this repository use from the settings file?* Only `distributionUrl`; `distributionSha256Sum` is not set.

## Further reading

- Apache Maven Wrapper: <https://maven.apache.org/tools/wrapper/> (wrapper 3.3.x)
- PowerShell documentation, about comment-based help and block comments (`about_Comments`): <https://learn.microsoft.com/powershell/module/microsoft.powershell.core/about/about_comments>
- Windows batch commands (`FOR /F`, `GOTO`, `IF`, `SET`): <https://learn.microsoft.com/windows-server/administration/windows-commands/windows-commands>
