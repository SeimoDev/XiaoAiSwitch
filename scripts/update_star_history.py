"""Plot daily GitHub star-count changes, without downloading stargazer identities."""

import json
import os
from datetime import UTC, date, datetime
from pathlib import Path
from urllib.request import Request, urlopen

ASSETS = Path(__file__).resolve().parents[1] / "assets"


def record(points, day, count):
    if not points:
        return [[day, count]]
    if points[-1][0] == day:
        if len(points) > 1 and points[-2][1] == count:
            return points[:-1]
        return [*points[:-1], [day, count]]
    if points[-1][1] == count:
        return points
    return [*points, [day, count]]


def render(points):
    first = date.fromisoformat(points[0][0])
    last = date.fromisoformat(points[-1][0])
    count = points[-1][1]
    ceiling = max(1, max(value for _, value in points))
    days = max(1, (last - first).days)
    steps = ["M 64 " + str(round(184 - 120 * points[0][1] / ceiling))]
    for day, value in points[1:]:
        x = 64 + round(566 * (date.fromisoformat(day) - first).days / days)
        y = round(184 - 120 * value / ceiling)
        steps.extend([f"H {x}", f"V {y}"])
    if len(points) == 1:
        steps.append("H 630")
    end_label = last.isoformat() if last != first else ""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 666 230" role="img" aria-labelledby="title">
  <title id="title">GitHub Star history: {count} Stars</title>
  <style>
    svg {{ --paper: #fff; --ink: #1f2328; --muted: #59636e; --rule: #d1d9e0; --line: #0969da; }}
    @media (prefers-color-scheme: dark) {{ svg {{ --paper: #0d1117; --ink: #f0f6fc; --muted: #9198a1; --rule: #30363d; --line: #58a6ff; }} }}
    text {{ font: 13px system-ui, sans-serif; fill: var(--muted); }}
    .title {{ font-size: 18px; font-weight: 600; fill: var(--ink); }}
  </style>
  <rect x="0.5" y="0.5" width="665" height="229" rx="12" fill="var(--paper)" stroke="var(--rule)"/>
  <text class="title" x="22" y="34">Star history</text>
  <text class="title" x="630" y="34" text-anchor="end">{count} Stars</text>
  <line x1="64" y1="64" x2="630" y2="64" stroke="var(--rule)" stroke-dasharray="4 4"/>
  <line x1="64" y1="184" x2="630" y2="184" stroke="var(--rule)"/>
  <text x="54" y="68" text-anchor="end">{ceiling}</text>
  <text x="54" y="188" text-anchor="end">0</text>
  <path d="{' '.join(steps)}" fill="none" stroke="var(--line)" stroke-width="3" stroke-linejoin="round"/>
  <text x="64" y="209">{first.isoformat()}</text>
  <text x="630" y="209" text-anchor="end">{end_label}</text>
</svg>
'''


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "SeimoDev/XiaoAiSwitch")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "XiaoAiSwitch-Star-History"}
    if os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    request = Request("https://api.github.com/repos/" + repo, headers=headers)
    with urlopen(request, timeout=30) as response:
        count = json.load(response)["stargazers_count"]
    data = ASSETS / "star-history.json"
    current = json.loads(data.read_text(encoding="utf-8")) if data.exists() else []
    points = record(current, datetime.now(UTC).date().isoformat(), count)
    data.write_text(json.dumps(points, indent=2) + "\n", encoding="utf-8", newline="\n")
    (ASSETS / "star-history.svg").write_text(render(points), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
