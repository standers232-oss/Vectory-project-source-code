Unicode True
Name "Nexus Mass PC"
OutFile "dist\NexusMassPC-Setup.exe"
InstallDir "$PROGRAMFILES64\Nexus Mass PC"
InstallDirRegKey HKCU "Software\NexusMassPC" "InstallDir"
RequestExecutionLevel admin

!define APP_EXE "NexusMassPC.exe"

Page directory
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

Section "Install"
  SetOutPath "$INSTDIR"
  File "dist\NexusMassPC.exe"
  File "README.md"
  WriteRegStr HKCU "Software\NexusMassPC" "InstallDir" "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\Nexus Mass PC"
  CreateShortcut "$SMPROGRAMS\Nexus Mass PC\Nexus Mass PC.lnk" "$INSTDIR\${APP_EXE}"
  CreateShortcut "$SMPROGRAMS\Nexus Mass PC\Удалить Nexus Mass PC.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut "$DESKTOP\Nexus Mass PC.lnk" "$INSTDIR\${APP_EXE}"
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\Nexus Mass PC.lnk"
  Delete "$SMPROGRAMS\Nexus Mass PC\Nexus Mass PC.lnk"
  Delete "$SMPROGRAMS\Nexus Mass PC\Удалить Nexus Mass PC.lnk"
  RMDir "$SMPROGRAMS\Nexus Mass PC"
  Delete "$INSTDIR\NexusMassPC.exe"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\NexusMassPC"
SectionEnd
