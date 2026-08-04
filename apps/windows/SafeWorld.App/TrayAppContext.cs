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
    private readonly UpdateService _updates = new();
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
        _updates.Changed += UpdateIcon;

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

        // Setup runs before the first app-update check, so a brand-new install answers its
        // questions rather than being interrupted by an update notice on top of them.
        ShowSetupIfNeeded();

        // Throttled to once a day inside the service, so this costs nothing on a restart.
        _ = _updates.CheckAsync();
    }

    private static Icon LoadEmbeddedIcon()
    {
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream("SafeWorld.App.Resources.SafeWorld.ico");
        return stream is not null ? new Icon(stream) : SystemIcons.Application;
    }

    private void UpdateIcon()
    {
        // The tray menu is rebuilt on Opening, so it cannot show download progress while it is
        // open. The tooltip can, and it is the only surface a tray app has that updates live.
        // NotifyIcon.Text is capped at 63 characters, which everything below stays well inside.
        var text = _updates.State switch
        {
            UpdateService.Status.Downloading when _updates.Percent >= 0 =>
                $"Safe World — downloading update {_updates.Percent}%",
            UpdateService.Status.Downloading => "Safe World — downloading update",
            UpdateService.Status.Available when _updates.Update is { } u => $"Safe World — version {u.Version} available",
            _ => _store.Settings.Enabled ? "Safe World — Protection on" : "Safe World — Protection off",
        };
        _icon.Text = text;
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

        AddBlockedCount(menu);
        menu.Items.Add(new ToolStripSeparator());

        // The three mandatory lists are **stated, not offered**.
        //
        // This menu used to draw a switch for all six categories. That was wrong for the same
        // reason it was wrong on the phones: installing Safe World is the decision to block scams,
        // gambling, and adult content, and a menu item that turns gambling back on in a weak
        // moment defeats the point of installing it. Windows has no PIN — it is the self-control
        // build — but "no lock on the door" is not a reason to hang the lever in easy reach.
        var always = string.Join(" · ", Categories.All.Where(c => !c.Optional).Select(c => c.Label));
        menu.Items.Add(new ToolStripMenuItem("Always blocked") { Enabled = false });
        menu.Items.Add(new ToolStripMenuItem($"   {always}") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        menu.Items.Add(new ToolStripMenuItem("Block more") { Enabled = false });
        foreach (var category in SubjectOrder)
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
        AddUpdateItems(menu);

        menu.Items.Add(new ToolStripSeparator());
        var quit = new ToolStripMenuItem("Quit Safe World");
        quit.Click += (_, _) => ExitThread();
        menu.Items.Add(quit);
    }

    /// <summary>
    /// Written out rather than taken from <c>Categories.Optional</c>, so the order is a UI
    /// decision — the same <c>subjectOrder</c> the other three platforms use. Games first; it is
    /// the one people come looking for.
    /// </summary>
    private static IEnumerable<CategoryMeta> SubjectOrder
    {
        get
        {
            var preferred = new[] { CategoryId.Games, CategoryId.Social, CategoryId.Entertainment };
            return Categories.Optional.OrderBy(c =>
            {
                var i = Array.IndexOf(preferred, c.Id);
                return i < 0 ? preferred.Length : i;
            });
        }
    }

    /// <summary>
    /// **Says something different when protection is off**, because the same sentence would be a
    /// lie: nothing is being blocked, and a line announcing 4.5 million blocked sites above a
    /// switch that is off is exactly the kind of reassurance this app must never give.
    /// </summary>
    private void AddBlockedCount(ContextMenuStrip menu)
    {
        if (_store.Settings.Enabled)
        {
            var count = _store.BlockedDomainCount();
            menu.Items.Add(new ToolStripMenuItem($"Blocking {CountFormat.Compact(count)} harmful sites") { Enabled = false });
            // The exact figure under the rounded one: the rounded number is the claim someone
            // repeats, this is the number that makes it checkable.
            menu.Items.Add(new ToolStripMenuItem($"   {CountFormat.Exact(count)} sites in total") { Enabled = false });
        }
        else
        {
            var count = _store.BlockedDomainCount(ifEnabled: true);
            menu.Items.Add(new ToolStripMenuItem("Protection is off") { Enabled = false });
            menu.Items.Add(new ToolStripMenuItem($"   Turn it on to block {CountFormat.Compact(count)} sites") { Enabled = false });
        }

        // Which of the two mechanisms is actually doing the work. The gap is a factor of thirty,
        // so it is worth saying rather than leaving the count to imply the better case.
        if (!_store.ProxyActive && _store.Proxy.Unavailable is { } why)
        {
            menu.Items.Add(new ToolStripMenuItem($"   Limited to the hosts file — {why}") { Enabled = false });
        }
    }

    /// <summary>The installed version, and whatever the update check has found.</summary>
    private void AddUpdateItems(ContextMenuStrip menu)
    {
        menu.Items.Add(new ToolStripMenuItem($"Version {UpdateService.InstalledVersion}") { Enabled = false });

        switch (_updates.State)
        {
            case UpdateService.Status.Checking:
                menu.Items.Add(new ToolStripMenuItem("Checking for updates…") { Enabled = false });
                break;

            case UpdateService.Status.UpToDate:
                menu.Items.Add(new ToolStripMenuItem("This is the latest version.") { Enabled = false });
                break;

            case UpdateService.Status.Available when _updates.Update is { } update:
                var size = update.SizeBytes > 0 ? $" ({update.SizeBytes / 1024 / 1024} MB)" : "";
                menu.Items.Add(new ToolStripMenuItem($"Version {update.Version} is available{size}") { Enabled = false });
                var get = new ToolStripMenuItem(
                    update.AssetUrl is null ? "View the release" : "Download the update");
                get.Click += async (_, _) => await _updates.DownloadAsync(update);
                menu.Items.Add(get);
                break;

            case UpdateService.Status.Downloading:
                var percent = _updates.Percent < 0 ? "" : $" {_updates.Percent}%";
                menu.Items.Add(new ToolStripMenuItem($"Downloading…{percent}") { Enabled = false });
                break;

            case UpdateService.Status.Downloaded:
                menu.Items.Add(new ToolStripMenuItem("Downloaded to your Downloads folder.") { Enabled = false });
                menu.Items.Add(new ToolStripMenuItem("Quit Safe World, then run it to finish.") { Enabled = false });
                break;

            case UpdateService.Status.Failed:
                menu.Items.Add(new ToolStripMenuItem($"⚠ {_updates.Message}") { Enabled = false });
                break;
        }

        if (_updates.State is UpdateService.Status.Idle or UpdateService.Status.UpToDate or UpdateService.Status.Failed)
        {
            var check = new ToolStripMenuItem("Check for updates");
            check.Click += async (_, _) => await _updates.CheckAsync(force: true);
            menu.Items.Add(check);
        }
    }

    /// <summary>
    /// First-run setup.
    /// <para>
    /// Before it, a fresh install put an icon in the notification area and waited. Everything
    /// optional was off and nothing said so — the app was working exactly as designed and looked,
    /// from the outside, like it did nothing. The phones learned this at 0.4.6; this is the same
    /// wizard, asked as one dialog because a tray app has no screen to own.
    /// </para>
    /// </summary>
    private void ShowSetupIfNeeded()
    {
        if (_store.OnboardingComplete) return;
        using var form = new SetupForm(_store);
        form.ShowDialog();
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
