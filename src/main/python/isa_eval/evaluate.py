import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from grpc._channel import _InactiveRpcError as InactiveRpcError
from grpc._channel import _MultiThreadedRendezvous as MultiThreadedRendezvous

from agent import EvalAgent, EvalAgentOutput
from client import IsaEvalClient, IsaSetup, ISA_PROOF_COMMANDS
from search import IsaBestFirstSearch, BestFirstSearch, SearchSummary
from utils import chop_by_condition, parse_root_file, prepare_logger


@dataclass
class EvalRecord:
    solved: bool
    proof_steps: List[str]
    search_summary: SearchSummary


def evaluate_single_theory(
    thy_path: Path,
    agent: EvalAgent,
    client: IsaEvalClient,
    solver: BestFirstSearch,
    logger: Optional[logging.Logger] = None,
) -> Dict[str, EvalRecord]:
    if logger is None:
        logger = prepare_logger(f"Evaluate-{thy_path.stem}")
    logger.setLevel(logging.DEBUG)

    logger.debug(f"Start evaluating theory file {thy_path}, parsing commands")
    # assume that the ITP is already set up
    try:
        commands = client.get_theory_commands(
            thy_path, only_statements=False, remove_ignored=True
        )
    except (InactiveRpcError, MultiThreadedRendezvous) as rpc_error:
        logger.warning(f"Failed to parse theory file {thy_path}: {rpc_error.details()}")
        return {}
    logger.debug(f"Parsing completed, got {len(commands)} commands")
    logger.debug(commands)

    # chop commands into groups where each group starts with a lemma
    # only applies to Isabelle
    assert (
        commands[-1].name == "end"
    ), f"last command should be 'end' but get {commands[-1].command}"
    grouped_commands = chop_by_condition(
        commands[:-1], lambda c: c.name in ISA_PROOF_COMMANDS
    )
    evaluation_records: Dict[str, EvalRecord] = {}

    # solve all lemmas
    for idx, group in enumerate(grouped_commands[1:]):

        logger.info(
            f"Ready to process {group[0].command} ({idx + 1} / {len(grouped_commands) - 1})"
        )

        logger.debug(group)

        try:
            if idx == 0:
                default_state = client.proceed_until(thy_path, group[0].command, 60)
            else:
                default_state = client.execute("default", group[0].command, 60)
        except (InactiveRpcError, MultiThreadedRendezvous) as rpc_error:
            logger.warning(
                f"Failed to proceed to {group[0].command}: {rpc_error.details()}"
            )
            return evaluation_records

        # try to prove the lemma, 'default' state is the only remaining state
        logger.info(f"Start searching with {solver.__class__.__name__}")

        # try:
        solved, proof_steps, search_summary = solver.solve(
            default_state, agent, client, ignore_duplicate_inputs=True
        )
        # except (InactiveRpcError, MultiThreadedRendezvous) as rpc_error:
        #     logger.warning(f"Error when trying to solve {group[0].command}: {rpc_error.details()}")
        #     return evaluation_records

        evaluation_records[group[0].command] = EvalRecord(
            solved, proof_steps, search_summary
        )

        logger.info(
            f"Solver {'succeeded' if solved else 'failed'} in "
            f"{search_summary.total_time} seconds ({group[0].command})"
        )

        # proceed to the next lemma, note that all errors are ignored
        for command in group[1:]:
            logger.debug(f"Executing {command.command}")
            try:
                default_state = client.execute("default", command.command, 60)
            except (InactiveRpcError, MultiThreadedRendezvous) as rpc_error:
                logger.warning(
                    f"Failed when executing {command.command}: {rpc_error.details()}"
                )
                return evaluation_records
            assert default_state.state_id == "default", "state_id should be 'default'"
            logger.debug(f"Default state: {default_state}")

    # only applies to Isabelle
    logger.info(f"Finishing theory file {thy_path}")

    try:
        print("last command:", commands[-1].command)
        if len(grouped_commands) > 1:
            print("executing last command end")
            default_state = client.execute("default", commands[-1].command, 60)
        else:
            print("strange, only one group?")
            default_state = client.proceed_until(thy_path, commands[-1].command, 60)
    except (InactiveRpcError, MultiThreadedRendezvous) as rpc_error:
        logger.warning(
            f"Failed when trying to finish theory file {thy_path}: {rpc_error.details()}"
        )
        return evaluation_records

    assert (
        default_state.state == "Mode: Toplevel"
    ), f"got {default_state.state} in {thy_path}"

    return evaluation_records


def prepare_setups(
    theories_path: Path,
) -> List[Tuple[str, Path, List[Path]]]:
    if Path.exists(theories_path / "ROOTS"):
        with open(theories_path / "ROOTS") as roots_file:
            entry_dirs = [x.strip() for x in roots_file.readlines() if x.strip() != ""]
        return sum(
            (prepare_setups(theories_path / entry) for entry in entry_dirs),
            [],
        )
    elif Path.exists(theories_path / "ROOT"):
        session_files_map = parse_root_file(theories_path / "ROOT")
        return [
            (session, theories_path, thy_files)
            for session, thy_files in session_files_map.items()
        ]
    all_files = list(theories_path.glob("**/*.thy"))
    pieces = [all_files[i: min(i + 50, len(all_files))] for i in range(0, len(all_files), 50)]
    return [(f"HOL", theories_path, piece) for piece in pieces]


def evaluate_isabelle_agent(
    isa_path: Path,
    theories_path: Path,
    agent: EvalAgent,
    solver: BestFirstSearch,
    session_roots: Optional[Path] = None,
    port: int = 8980,
    logger: Optional[logging.Logger] = None,
) -> Tuple[Dict[Tuple[str, str, Path], EvalRecord], Dict[Tuple[str, Path], float]]:
    if logger is None:
        logger = prepare_logger("Evaluate")

    eval_time_dict: Dict[Tuple[str, Path], float] = {}

    client = IsaEvalClient(port)
    final_eval_records: Dict[Tuple[str, str, Path], EvalRecord] = {}
    for session, wd, thy_files in prepare_setups(theories_path):
        setup = IsaSetup(isa_path, session, wd, session_roots)
        time_before_eval = time.time()
        logger.info(f"Setting up ITP (session {setup.session} with {setup.isa_path})")

        try:
            client.open_stub()
            client.setup_itp(setup)
        except InactiveRpcError as rpc_error:
            logger.warning(f"Failed to setup ITP: {rpc_error.details()}")
            continue
        finally:
            logger.info(
                f"ITP setup finished in {time.time() - time_before_eval:.2f} seconds"
            )

        for thy_path in thy_files:
            time_before_eval = time.time()
            eval_record = evaluate_single_theory(thy_path, agent, client, solver)
            eval_time_dict[(session, thy_path)] = time.time() - time_before_eval
            final_eval_records.update(
                {(key, session, thy_path): value for key, value in eval_record.items()}
            )

        client.close_itp()

    return final_eval_records, eval_time_dict


def pretty_print_eval_summary(
    records: Dict[Tuple[str, str, Path], EvalRecord],
    times: Dict[Tuple[str, Path], float],
):
    num = len(records)
    solved_count = sum(r.solved for r in records.values())
    generated_cmd_count = sum(r.search_summary.generated_num for r in records.values())
    succeeded_cmd_count = sum(r.search_summary.succeeded_num for r in records.values())
    query_count = sum(r.search_summary.query_count for r in records.values())
    timeout_count = sum(r.search_summary.timeout_count for r in records.values())
    avg_eval_time = sum(times.values()) / len(times)
    avg_search_query_time = (
        sum(r.search_summary.agent_query_time for r in records.values()) / num
    )
    avg_search_itp_running_time = (
        sum(r.search_summary.itp_running_time for r in records.values()) / num
    )
    avg_proof_steps_length = (
        sum(len(r.proof_steps) for r in records.values() if r.solved) / solved_count
    )
    avg_search_time = sum(r.search_summary.total_time for r in records.values()) / num
    print(f"Solved {solved_count} out of {num} lemmas")
    print(f"Generated {generated_cmd_count} commands, succeeded {succeeded_cmd_count}")
    print(f"Total query count: {query_count}, timeout count: {timeout_count}")
    print(f"Average evaluation time (each file): {avg_eval_time:.4f} seconds")
    print(f"Average generated proof length: {avg_proof_steps_length:.4f}")
    print(
        f"Average search time: {avg_search_time:.4f} seconds"
        f" (query {avg_search_query_time:.4f} / ITP {avg_search_itp_running_time:.4f})"
    )


if __name__ == "__main__":
    import random

    class SimpleAgent(EvalAgent):
        def query(self, state: str, gen_length: int) -> List[EvalAgentOutput]:
            return [
                EvalAgentOutput("by auto", random.random()) for _ in range(gen_length)
            ]

    class SledgehammerAgent(EvalAgent):
        def query(self, state: str, gen_length: int) -> List[EvalAgentOutput]:
            return [
                EvalAgentOutput("sledgehammer", random.random())
                for _ in range(gen_length)
            ]

    def test():
        isa_path = Path("~/opt/Isabelle2023").expanduser()
        theories_path = Path("/home/xiaokun/projects/isa-eval/dataset/miniF2F/isabelle/valid")
        # theories_path = Path("/home1/afp-repo/afp-2023/thys/Completeness")
        # theories_path = Path("/home/xiaokun/projects/isa-eval/src/main/resources")
        session_roots = Path("/home1/afp-repo/afp-2023/thys")
        # session_roots = None
        agent = SimpleAgent()
        solver = IsaBestFirstSearch(step_timeout=10)
        eval_records, times_dict = evaluate_isabelle_agent(
            isa_path, theories_path, agent, solver, session_roots
        )

        pretty_print_eval_summary(eval_records, times_dict)

    test()


