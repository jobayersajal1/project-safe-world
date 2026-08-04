using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// First-run setup, one question at a time — the Windows half of Android's
/// <c>OnboardingScreen</c>, iOS's <c>OnboardingView</c>, and macOS's <c>SetupView</c>, and
/// deliberately the same questions in the same order.
/// <para>
/// <b>Two of the phones' steps are absent, and neither is an oversight.</b> There is no language
/// step: this app is English-only (see <see cref="CountFormat"/>). And there is no PIN step:
/// Windows is the self-control build, stated as such in the tray menu.
/// </para>
/// <para>
/// What Windows has instead is a step the phones do not need — "start with Windows" — because this
/// app's protection ends at the next reboot if it is not registered, and the only symptom is
/// blocked sites quietly working again.
/// </para>
/// </summary>
internal sealed class SetupForm : Form
{
    private readonly SettingsStore _store;

    private readonly Label _stepLabel = new() { AutoSize = true, Top = 16, Left = 24 };
    private readonly ProgressBar _progress = new() { Top = 40, Left = 24, Width = 412, Height = 6, Style = ProgressBarStyle.Continuous };
    private readonly Label _title = new() { AutoSize = false, Top = 60, Left = 24, Width = 412, Height = 32, Font = new Font(DefaultFont.FontFamily, 15, FontStyle.Bold) };
    private readonly Label _body = new() { AutoSize = false, Top = 100, Left = 24, Width = 412, Height = 110 };
    private readonly Button _yes = new() { Top = 228, Left = 24, Width = 200, Height = 32 };
    private readonly Button _no = new() { Top = 228, Left = 236, Width = 200, Height = 32 };

    private int _step;

    /// <summary>
    /// The steps, in order. A list rather than raw indices so adding one later cannot silently
    /// renumber the rest of the "step N of M" header.
    /// </summary>
    private enum Step { Welcome, Games, Social, Entertainment, Startup, Finish }

    private static readonly Step[] Steps =
    {
        Step.Welcome, Step.Games, Step.Social, Step.Entertainment, Step.Startup, Step.Finish,
    };

    public SetupForm(SettingsStore store)
    {
        _store = store;

        Text = "Set up Safe World";
        ClientSize = new Size(460, 280);
        StartPosition = FormStartPosition.CenterScreen;
        MinimizeBox = false;
        MaximizeBox = false;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        // Setup is the first thing a new install does; it must not open behind the window the user
        // was already looking at.
        TopMost = true;

        Controls.AddRange(new Control[] { _stepLabel, _progress, _title, _body, _yes, _no });
        Render();
    }

    private void Render()
    {
        var current = Steps[_step];
        _stepLabel.Text = $"Step {_step + 1} of {Steps.Length}";
        _progress.Maximum = Steps.Length;
        _progress.Value = _step + 1;

        switch (current)
        {
            case Step.Welcome:
                // States what is already covered before asking for anything. Setup does not ask
                // about the mandatory three — asking would imply an answer of "no" exists.
                _title.Text = "Safe World is on";
                _body.Text =
                    "Scams and malware, gambling, and adult content are blocked from now on. "
                    + "Those three aren't optional — blocking them is what this app is for.\r\n\r\n"
                    + "The next few questions are about what else you'd like gone.";
                SetButtons("Continue", null);
                break;

            case Step.Games:
                Ask("Block games?", "Game portals, browser games, and gaming platforms.");
                break;

            case Step.Social:
                Ask("Block social media?",
                    "Facebook, Instagram, X/Twitter, TikTok, and similar feeds — including dating sites.");
                break;

            case Step.Entertainment:
                Ask("Block entertainment?", "YouTube, Netflix, and other streaming and video sites.");
                break;

            case Step.Startup:
                _title.Text = "Start with Windows?";
                _body.Text =
                    "Safe World does the blocking while it is running. If it doesn't start with "
                    + "Windows, protection ends at the next restart — and the only sign of that is "
                    + "blocked sites quietly working again.";
                SetButtons("Yes, start automatically", "No");
                break;

            default:
                _title.Text = "You're set up";
                _body.Text =
                    "Safe World lives in the notification area — click the shield to change "
                    + "anything, or to turn protection off.";
                SetButtons("Done", null);
                break;
        }
    }

    private void Ask(string title, string body)
    {
        _title.Text = title;
        _body.Text = body;
        SetButtons("Block it", "Leave it");
    }

    private void SetButtons(string yes, string? no)
    {
        _yes.Text = yes;
        _yes.Click -= OnYes;
        _yes.Click += OnYes;

        _no.Visible = no is not null;
        if (no is not null)
        {
            _no.Text = no;
            _no.Click -= OnNo;
            _no.Click += OnNo;
        }

        // A single button spans the full width rather than sitting half-width with dead space
        // beside it.
        _yes.Width = no is null ? 412 : 200;
        AcceptButton = _yes;
    }

    private void OnYes(object? sender, EventArgs e) => Answer(true);
    private void OnNo(object? sender, EventArgs e) => Answer(false);

    private void Answer(bool yes)
    {
        switch (Steps[_step])
        {
            case Step.Games:
                SetCategory(CategoryId.Games, yes);
                break;
            case Step.Social:
                SetCategory(CategoryId.Social, yes);
                break;
            case Step.Entertainment:
                SetCategory(CategoryId.Entertainment, yes);
                break;
            case Step.Startup:
                if (yes) StartupManager.Register();
                else StartupManager.Unregister();
                break;
            case Step.Finish:
                _store.CompleteOnboarding();
                Close();
                return;
        }

        _step++;
        Render();
    }

    /// <summary>
    /// Writes <c>false</c> rather than just advancing.
    /// <para>
    /// Only ever setting <c>true</c> made "leave it" mean "keep whatever was there", so a second
    /// run of setup could not turn anything back off. Same fix as iOS's and macOS's — the answer
    /// has to mean what it says.
    /// </para>
    /// </summary>
    private void SetCategory(CategoryId id, bool blocked) =>
        _store.Update(s => s.Categories[id] = blocked);
}
