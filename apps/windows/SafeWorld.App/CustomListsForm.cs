namespace SafeWorld.App;

/// <summary>Small editor for the custom allow/block lists, the Windows analogue of iOS's `SettingsView`.</summary>
internal sealed class CustomListsForm : Form
{
    private readonly SettingsStore _store;
    private readonly TextBox _allowBox;
    private readonly TextBox _blockBox;

    public CustomListsForm(SettingsStore store)
    {
        _store = store;

        Text = "Safe World — Custom Lists";
        ClientSize = new Size(404, 440);
        StartPosition = FormStartPosition.CenterScreen;
        MinimizeBox = false;
        MaximizeBox = false;
        FormBorderStyle = FormBorderStyle.FixedDialog;

        var allowLabel = new Label { Text = "Always allow (one domain per line):", AutoSize = true, Top = 10, Left = 10 };
        _allowBox = new TextBox
        {
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            Top = 32,
            Left = 10,
            Width = 384,
            Height = 160,
            Text = string.Join(Environment.NewLine, store.Settings.CustomAllow),
        };

        var blockLabel = new Label { Text = "Always block (one domain per line):", AutoSize = true, Top = 202, Left = 10 };
        _blockBox = new TextBox
        {
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            Top = 224,
            Left = 10,
            Width = 384,
            Height = 160,
            Text = string.Join(Environment.NewLine, store.Settings.CustomBlock),
        };

        var saveButton = new Button { Text = "Save", Top = 398, Left = 234, Width = 80 };
        saveButton.Click += (_, _) => Save();

        var cancelButton = new Button { Text = "Cancel", Top = 398, Left = 314, Width = 80 };
        cancelButton.Click += (_, _) => Close();

        Controls.AddRange(new Control[] { allowLabel, _allowBox, blockLabel, _blockBox, saveButton, cancelButton });
        AcceptButton = saveButton;
        CancelButton = cancelButton;
    }

    private static List<string> ParseLines(string text) =>
        text.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .Select(l => l.Trim())
            .Where(l => l.Length > 0)
            .ToList();

    private void Save()
    {
        _store.Update(s =>
        {
            s.CustomAllow = ParseLines(_allowBox.Text);
            s.CustomBlock = ParseLines(_blockBox.Text);
        });
        Close();
    }
}
