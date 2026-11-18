; Inno Setup Script for RemotePad PC Agent (+ automatic VB-Cable Virtual Audio Driver)
; Produces: dist\RemotePad-Setup.exe

#define MyAppName "RemotePad"
#define MyAppFullName "RemotePad PC Agent"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "RemotePad"
#define MyAppExeName "RemotePad.exe"
#define MyAppUserModelID "RemotePad.PCAgent"

[Setup]
AppId={{8E3C6B2A-4D7F-4A1E-9B5C-2F8A1E6D3C4B}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppFullName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
; Admin rights are required to silently install the VB-Cable kernel audio driver
PrivilegesRequired=admin
UsedUserAreasWarning=no
OutputDir=..\dist
OutputBaseFilename=RemotePad-Setup
SetupIconFile=..\assets\app_icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppFullName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
AppMutex=Local\RemotePad_PC_Agent_SingleInstance_v1
CloseApplications=force
CloseApplicationsFilter=*.exe
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "autostart"; Description: "Start {#MyAppName} automatically at Windows boot (works before unlock / on Lock Screen)"; GroupDescription: "Startup options:"
Name: "vbcable"; Description: "Install VB-Cable Virtual Audio Driver (required for Phone Mic)"; GroupDescription: "Audio Driver:"; Check: not IsVBCableInstalled
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; RemotePad PC Agent application files
Source: "..\dist\RemotePad\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; VB-Cable Driver Pack 46 (extracted to temp and removed automatically after driver install)
Source: "..\build\vbcable_driver\*"; DestDir: "{tmp}\vbcable"; Flags: ignoreversion recursesubdirs createallsubdirs deleteafterinstall; Tasks: vbcable

[Icons]
; Start Menu shortcut (makes "RemotePad" immediately searchable in Windows Search bar)
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Comment: "RemotePad PC Agent — Control your PC from phone"; AppUserModelID: "{#MyAppUserModelID}"
; Optional Desktop shortcut
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Comment: "RemotePad PC Agent"; AppUserModelID: "{#MyAppUserModelID}"; Tasks: desktopicon

[Registry]
; Clean up any legacy non-elevated HKCU Run entry (elevated Task Scheduler is used instead)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "{#MyAppName}"; Flags: deletevalue

[Run]
; Silently install VB-Cable Virtual Audio Driver (only if not already installed)
Filename: "{tmp}\vbcable\VBCABLE_Setup_x64.exe"; Parameters: "-i -h"; WorkingDir: "{tmp}\vbcable"; StatusMsg: "Installing VB-Cable Virtual Audio Driver (for Phone Mic)..."; Flags: runhidden waituntilterminated; Tasks: vbcable; AfterInstall: MarkVBCableInstalled
; Register pre-login SYSTEM boot service (RemotePadService) + user tray logon task (RemotePad)
Filename: "{app}\{#MyAppExeName}"; Parameters: "--install-autostart"; StatusMsg: "Configuring Windows pre-login auto-startup service..."; Flags: runhidden waituntilterminated; Tasks: autostart
; Start the SYSTEM boot service right away so Lock Screen & Admin control work immediately
Filename: "{app}\{#MyAppExeName}"; Parameters: "--start-service"; StatusMsg: "Starting RemotePad background service..."; Flags: runhidden waituntilterminated; Tasks: autostart
; Launch RemotePad Tray UI right after installation finishes (attaches seamlessly to the running service)
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\{#MyAppExeName}"; Parameters: "--remove-autostart"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveRemotePadAutostart"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
var
  VBCableFreshlyInstalled: Boolean;

function IsVBCableInstalled(): Boolean;
begin
  Result :=
    RegKeyExists(HKLM, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\VB:VBCABLE {87459874-1236-4469}') or
    RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\VBAudioVACMME') or
    RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\VBAudioVACWDM');
end;

procedure MarkVBCableInstalled();
begin
  if IsVBCableInstalled() then
    VBCableFreshlyInstalled := True;
end;

function NeedRestart(): Boolean;
begin
  // Only prompt for Windows restart if VB-Cable driver was freshly installed during this setup
  Result := VBCableFreshlyInstalled;
end;

// Gracefully stop the SYSTEM boot service and any running RemotePad.exe instance before install or uninstall
procedure StopRunningRemotePad();
var
  ResultCode: Integer;
  InstalledExe: String;
begin
  InstalledExe := ExpandConstant('{app}\{#MyAppExeName}');
  if FileExists(InstalledExe) then
    Exec(InstalledExe, '--stop-service', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\schtasks.exe'), '/End /TN RemotePadService', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM RemotePad.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  VBCableFreshlyInstalled := False;
  StopRunningRemotePad();
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  StopRunningRemotePad();
  Result := True;
end;
