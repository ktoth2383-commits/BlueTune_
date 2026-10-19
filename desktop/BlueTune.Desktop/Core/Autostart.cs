using Microsoft.Win32;

namespace BlueTune.Desktop.Core;

/// <summary>Indítás a Windows-zal (HKCU\...\Run – nem kell rendszergazdai jog).</summary>
public static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "BlueTune";

    public static void Sync()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true)
                            ?? Registry.CurrentUser.CreateSubKey(RunKey);
            if (Store.Prefs.StartWithWindows && Environment.ProcessPath is { } exe)
                key.SetValue(ValueName, $"\"{exe}\" --tray");   // a mostani helyre mutat (ha áthelyezted az exe-t)
            else if (key.GetValue(ValueName) != null)
                key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        catch { /* pl. csoportházirend tiltja – nem kritikus */ }
    }
}
