using System.Reflection;
using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// The whole UI: a tray (notification area) icon with a right-click menu for the master switch,
/// per-category toggles, and custom lists — the Windows analogue of the extension's popup /
/// iOS's `HomeView`, minus a "blocked today" counter (a hosts-file sinkhole has no visibility into
/// individual blocked requests to count).
/// </summary>
internal sealed class TrayAppContext : ApplicationContext
{
    private static readonly Icon AppIcon = LoadEmbeddedIcon();

    private readonly NotifyIcon _icon;
    private readonly SettingsStore _store = new();
    private readonly System.Windows.Forms.Timer _remoteUpdateTimer;
    private CustomListsForm? _listsForm;

    public TrayAppContext()
    {
        _icon = new NotifyIcon
        {
            Visible = true,
            Icon = AppIcon,
            ContextMenuStrip = new ContextMenuStrip(),
        };
        _icon.ContextMenuStrip!.Opening += (_, _) => RebuildMenu();
        _icon.DoubleClick += (_, _) => ShowCustomLists();
        _store.Changed += UpdateIcon;

        // The proxy lives in this process, so "does the app start with Windows" and "is the
        // machine protected after a reboot" are the same question.
        StartupManager.EnsureRegistered();

        UpdateIcon();
        RebuildMenu();

        // RefreshRemoteIfDueAsync no-ops until 24h since the last fetch, so this just needs to
        // run often enough to catch that boundary — an hourly check is plenty, and running it on
        // this UI-thread System.Windows.Forms.Timer (rather than a background thread/timer) means
        // the continuation after `await` stays on the UI thread, so touching the NotifyIcon from
        // `Changed` afterward is safe without extra marshaling.
        _remoteUpdateTimer = new System.Windows.Forms.Timer { Interval = (int)TimeSpan.FromHours(1).TotalMilliseconds };
        _remoteUpdateTimer.Tick += async (_, _) => await _store.RefreshRemoteIfDueAsync();
        _remoteUpdateTimer.Start();
        _ = _store.RefreshRemoteIfDueAsync();
    }

    private static Icon LoadEmbeddedIcon()
    {
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream("SafeWorld.App.Resources.SafeWorld.ico");
        return stream is not null ? new Icon(stream) : SystemIcons.Application;
    }

    private void UpdateIcon()
    {
        _icon.Text = _store.Settings.Enabled ? "Safe World — Protection on" : "Safe World — Protection off";
    }

    private void RebuildMenu()
    {
        var menu = _icon.ContextMenuStrip!;
        menu.Items.Clear();

        menu.Items.Add(new ToolStripMenuItem("Safe World") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        var protection = new ToolStripMenuItem("Protection") { Checked = _store.Settings.Enabled };
        protection.Click += (_, _) => _store.Update(s => s.Enabled = !s.Enabled);
        menu.Items.Add(protection);
        menu.Items.Add(new ToolStripMenuItem("(Self-control model — no PIN lock)") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        foreach (var category in Categories.All)
        {
            var isOn = _store.Settings.Categories.TryGetValue(category.Id, out var on) && on;
            var item = new ToolStripMenuItem(category.Label)
            {
                Checked = isOn,
                Enabled = _store.Settings.Enabled,
                ToolTipText = category.Description,
            };
            item.Click += (_, _) => _store.Update(s => s.Categories[category.Id] = !isOn);
            menu.Items.Add(item);
        }

        menu.Items.Add(new ToolStripSeparator());

        // Shown rather than assumed: if this is off, protection ends at the next restart, and the
        // only symptom is blocked sites quietly working again.
        var startWithWindows = new ToolStripMenuItem("Start with Windows")
        {
            Checked = StartupManager.IsRegistered(),
        };
        startWithWindows.Click += (_, _) =>
        {
            if (startWithWindows.Checked) StartupManager.Unregister();
            else StartupManager.Register();
        };
        menu.Items.Add(startWithWindows);

        menu.Items.Add(new ToolStripSeparator());
        var editLists = new ToolStripMenuItem("Edit custom lists...");
        editLists.Click += (_, _) => ShowCustomLists();
        menu.Items.Add(editLists);

        if (_store.LastSyncError is { } error)
        {
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem($"⚠ {error}") { Enabled = false });
        }

        menu.Items.Add(new ToolStripSeparator());
        var quit = new ToolStripMenuItem("Quit Safe World");
        quit.Click += (_, _) => ExitThread();
        menu.Items.Add(quit);
    }

    private void ShowCustomLists()
    {
        if (_listsForm is { IsDisposed: false })
        {
            _listsForm.Activate();
            return;
        }
        _listsForm = new CustomListsForm(_store);
        _listsForm.Show();
    }

    protected override void ExitThreadCore()
    {
        _remoteUpdateTimer.Stop();
        _icon.Visible = false;
        base.ExitThreadCore();
    }
}
