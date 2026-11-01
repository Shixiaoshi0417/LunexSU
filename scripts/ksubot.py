import asyncio
import json
import os
from pathlib import Path
import sys

API_ID = 611335
API_HASH = "d524b414d21f4d37f08684c1df41ac9c"


def get_caption(event, title, version, branch, run_url):
    commits = event.get("commits")
    if commits is None:
        head = event.get("head_commit")
        commits = [head] if head else []
    lines = []
    for commit in reversed(commits):
        message_lines = commit.get("message", "").splitlines() or [""]
        message = message_lines[0].strip()
        if len(message_lines) > 1:
            message += " [..]"
        if len(message) > 100:
            message = message[:97] + "..."
        author = commit.get("author") or {}
        name = author.get("username") or author.get("name") or "unknown author"
        line = f"{message} by {name}"
        if sum(len(item) + 1 for item in lines) + len(line) > 600:
            lines.insert(0, f"(other {len(commits) - len(lines)} commits)")
            break
        lines.insert(0, line)
    commit_message = "```\n" + "\n".join(lines) + "\n```\n" if lines else ""
    head = event.get("head_commit") or {}
    compare = event.get("compare")
    commit_url = compare or head.get("url")
    commit_link = f"[{'Compare' if compare else 'Commit'}]({commit_url})\n" if commit_url else ""
    warning = ""
    if branch == "dev":
        warning = "\n⚠️⚠️**DEV VERSION, PLEASE BACKUP BEFORE INSTALLATION**⚠️⚠️"
        warning += "\n⚠️⚠️**测试版，安装前请备份**⚠️⚠️"
    message = (f"**{title}**\nBranch: {branch}\n#ci_{version}\n"
               f"{commit_message}{commit_link}[Workflow run]({run_url}){warning}")
    # Telegram counts UTF-16 code units. Include the DEV warning in the limit.
    if len(message.encode("utf-16-le")) // 2 > 1024:
        message = f"[Workflow run]({run_url}){warning}"
    if len(message.encode("utf-16-le")) // 2 > 1024:
        raise ValueError("Workflow URL exceeds the caption limit")
    return message


def required_environment(name):
    value = os.environ.get(name)
    if not value:
        raise ValueError(f"Missing {name}")
    return value


async def main():
    # Read credentials only for the real upload, never during import or tests of
    # event formatting. None of the credentials are included in diagnostics.
    token = required_environment("BOT_TOKEN")
    chat = required_environment("CHAT_ID")
    try:
        chat = int(chat)
    except ValueError:
        pass
    thread = os.environ.get("MESSAGE_THREAD_ID")
    thread = int(thread) if thread else None
    caption = get_caption(
        json.loads(os.environ.get("GITHUB_EVENT", "{}")),
        required_environment("TITLE"), required_environment("VERSION"),
        required_environment("BRANCH"), required_environment("RUN_URL"),
    )
    files = sys.argv[1:]
    if not files:
        raise ValueError("No files to upload")

    from telethon import TelegramClient

    session = str(Path(__file__).resolve().parent / "ksubot")
    print("[+] Uploading to telegram")
    async with await TelegramClient(session=session, api_id=API_ID, api_hash=API_HASH).start(bot_token=token) as bot:
        captions = [""] * (len(files) - 1) + [caption]
        await bot.send_file(entity=chat, file=files, caption=captions,
                            reply_to=thread, parse_mode="markdown")
    print("[+] Done!")


def run():
    try:
        asyncio.run(main())
    except Exception as error:
        # Exception text from a client can contain credentials or request URLs.
        print(f"[-] Telegram upload failed ({type(error).__name__})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
