from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_ROOT = REPOSITORY_ROOT / ".github" / "workflows"


def test_workflow_templates_do_not_escape_github_or_shell_interpolation():
    workflow_files = sorted(WORKFLOW_ROOT.glob("*.yml"))
    assert workflow_files, "GitHub Actions workflow files must be present"

    escaped = []
    for workflow in workflow_files:
        lines = workflow.read_text(encoding="utf-8").splitlines()
        for line_number, line in enumerate(lines, start=1):
            if r"\${{" in line or r"\${" in line:
                escaped.append(
                    f"{workflow.relative_to(REPOSITORY_ROOT)}:{line_number}: {line}"
                )

    assert not escaped, (
        "Escaped GitHub Actions or shell interpolation found:\n" + "\n".join(escaped)
    )
