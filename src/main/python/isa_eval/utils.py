import logging
import re
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional


def balanced_pairs(
    string: str, left: str, right: str, ignores: Optional[List[str]] = None
) -> bool:
    if ignores is None:
        ignores = []
    assert all(
        not left.startswith(ignore) for ignore in ignores
    ), "ignored string should not be prefix of left"
    assert all(
        not right.startswith(ignore) for ignore in ignores
    ), "ignored string should not be prefix of right"
    stack = []
    remaining = string
    while len(remaining) > 0:
        print(remaining, stack)
        if remaining.startswith(left):
            stack.append(left)
            remaining = remaining[len(left) :]
        elif remaining.startswith(right):
            if len(stack) == 0:
                return False
            stack.pop()
            remaining = remaining[len(right) :]
        elif any(remaining.startswith(ignore) for ignore in ignores):
            longest_ignore = max(
                (ignore for ignore in ignores if remaining.startswith(ignore)), key=len
            )
            print(longest_ignore)
            remaining = remaining[len(longest_ignore) :]
        else:
            remaining = remaining[1:]
    return len(stack) == 0 or (left == right and len(stack) % 2 == 0)


def is_inner_term(string: str) -> bool:
    return (
        string.startswith('"')
        and string.endswith('"')
        and balanced_pairs(string, '"', '"', ['\\"'])
    ) or (
        string.startswith("\\<open>")
        and string.endswith("\\<close>")
        and balanced_pairs(string, "\\<open>", "\\<close>")
    )


def chop_by_condition(
    seq: Iterable[Any], condition: Callable[[Any], bool]
) -> List[List[Any]]:
    result = []
    current = []
    for elem in seq:
        if condition(elem):
            if len(current) > 0:
                result.append(current)
                current = []
        current.append(elem)
    if len(current) > 0:
        result.append(current)
    return result


def parse_root_file(root_path: Path) -> Dict[str, List[Path]]:
    entry_path = root_path.parent
    root_text = root_path.read_text(encoding="utf-8")
    dir2session = {}
    session_lst = []
    main_dir_lst = []
    session_idx_lst = []

    session_regex = re.compile(
        r"session\s+([\w_\"+-]+)\s*(?:\(.*?\)\s*)?(?:in\s*(.*?)\s*)?=.*?"
    )
    for match in session_regex.finditer(root_text):
        session_name = match.group(1).strip('"')
        main_dir: str = match.group(2).strip('"') if match.group(2) is not None else ""
        session_lst.append(session_name)
        main_dir_lst.append(entry_path / main_dir)
        session_idx_lst.append(match.start(1))
    session_idx_lst.append(len(root_text))

    dir_regex = re.compile(r"directories\s*\n([\s\S]*?)theories")
    for i, (session, folder) in enumerate(zip(session_lst, main_dir_lst)):
        dir2session[folder] = session
        dir_match = dir_regex.search(
            root_text, session_idx_lst[i], session_idx_lst[i + 1]
        )
        if dir_match is not None:
            for d in dir_match.group(1).strip().split():
                dir2session[Path.resolve(folder / d.strip())] = session

    session2files = {s: [] for s in session_lst}
    for thy_path in entry_path.glob("**/*.thy"):
        parent = thy_path.parent
        while parent not in dir2session:
            parent = parent.parent
        session = dir2session[parent]
        session2files[session].append(thy_path)

    return session2files


def prepare_logger(name: str, log_file: Optional[Path] = None) -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    formatter = logging.Formatter("%(asctime)s %(name)s %(levelname)s %(message)s")
    if log_file is not None:
        file_handler = logging.FileHandler(log_file)
        file_handler.setLevel(logging.DEBUG)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.DEBUG)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    return logger


if __name__ == "__main__":
    assert balanced_pairs('"p ==> \\"q\\""', '"', '"', ['\\"'])
    print(chop_by_condition([1, 2, 3, 4, 4, 5, 6, 7, 8, 9], lambda x: x % 2222 == 0))

    session_files_map = parse_root_file(
        Path("/home1/afp-repo/afp-2023/thys/Ordinary_Differential_Equations/ROOT")
    )
    for k, v in session_files_map.items():
        print(k, v)
