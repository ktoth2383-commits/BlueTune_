using BlueTune.Desktop.Audio;
using BlueTune.Desktop.Core;
using BlueTune.Desktop.UI;

namespace BlueTune.Desktop;

internal static class Program
{
    private const string MutexName = @"Local\BlueTune.Desktop.SingleInstance";
    private const string ShowEventName = @"Local\BlueTune.Desktop.Show";

    [STAThread]
    private static int Main(string[] args)
    {
        // Rendszergazdaként indított segédfeladatok (UAC után) – ablak nélkül futnak és kilépnek
        if (args.Contains("--setup-apo")) return EqualizerApo.SetupAsAdmin();
        if (args.Contains("--restore-apo")) return EqualizerApo.RestoreAsAdmin();

        // Csak egy példány fusson: a második csak előhozza az elsőt
        using var mutex = new Mutex(true, MutexName, out var isFirst);
        if (!isFirst)
        {
            try
            {
                using var ev = EventWaitHandle.OpenExisting(ShowEventName);
                ev.Set();
            }
            catch { /* az első példány épp kilép */ }
            return 0;
        }

        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => LogError(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => LogError(e.ExceptionObject as Exception);

        Store.Load();
        Autostart.Sync();

        var ctx = new TrayContext(startHidden: args.Contains("--tray"));

        // Háttérszál: vár a második példány jelzésére
        var waiter = new Thread(() =>
        {
            try
            {
                while (showEvent.WaitOne()) ctx.RequestShow();
            }
            catch (ObjectDisposedException) { }
        })
        { IsBackground = true, Name = "BlueTune-show" };
        waiter.Start();

        Application.Run(ctx);
        GC.KeepAlive(mutex);
        return 0;
    }

    private static void LogError(Exception? ex)
    {
        if (ex == null) return;
        try
        {
            Directory.CreateDirectory(Store.Dir);
            File.AppendAllText(Path.Combine(Store.Dir, "error.log"), $"[{DateTime.Now:u}] {ex}\r\n\r\n");
        }
        catch { /* nincs hova írni */ }
    }
}
