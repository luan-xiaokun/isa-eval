import heapq
import logging
import re
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple

from agent import EvalAgent, EvalAgentOutput
from client import EvalClient, ITPState, IsaState
from utils import prepare_logger


@dataclass
class SNode:
    score: float
    proof_steps: List[str]
    state: ITPState

    @property
    def state_id(self):
        return self.state.state_id

    def __lt__(self, other):
        return self.score < other.score


@dataclass
class SearchSummary:
    succeeded_num: int = 0
    generated_num: int = 0
    query_count: int = 0
    timeout_count: int = 0
    itp_running_time: float = 0.0
    agent_query_time: float = 0.0
    total_time: float = 0.0
    failure_reason: Optional[str] = None

    def __str__(self):
        text = ""
        text += f"total time {self.total_time:.2f} seconds "
        text += (
            f"(itp {self.itp_running_time:.2f}, agent {self.agent_query_time:.2f}); "
        )
        text += f"query {self.query_count}, timeout {self.timeout_count}; "
        text += f"commands {self.succeeded_num} / {self.generated_num}"
        if self.failure_reason:
            text += f"; failed due to {self.failure_reason}"
        return text


class BestFirstSearch:
    def __init__(
        self,
        gen_length: int = 16,
        query_limit: int = 300,
        queue_length: int = 32,
        step_timeout: float = 10.0,
        total_timeout: float = 600.0,
        step_timeout_limit: int = 60,
        logger: Optional[logging.Logger] = None,
    ):
        self.gen_length = gen_length
        self.query_limit = query_limit
        self.queue_length = queue_length
        self.step_timeout = step_timeout
        self.total_timeout = total_timeout
        self.step_timeout_limit = step_timeout_limit
        if logger is None:
            logger = prepare_logger(self.__class__.__name__)

        self.logger = logger

        for attribute in [
            "gen_length",
            "query_limit",
            "queue_length",
            "step_timeout",
            "total_timeout",
            "step_timeout_limit",
        ]:
            self.logger.info(f"{attribute}: {getattr(self, attribute)}")

    @staticmethod
    def filter_agent_outputs(outputs: List[EvalAgentOutput]) -> List[EvalAgentOutput]:
        sorry_oops_pattern = re.compile(r"\bsorry\b|\boops\b ")
        deduplicated_commands = set()
        results = []
        for output in outputs:
            sanitized_cmd = output.command.strip()
            if (
                re.search(sorry_oops_pattern, sanitized_cmd) is None
                and sanitized_cmd not in deduplicated_commands
            ):
                deduplicated_commands.add(sanitized_cmd)
                results.append(output)
        return results

    @staticmethod
    def make_input(*args, **kwargs) -> str:
        pass

    @staticmethod
    def get_command(output: EvalAgentOutput, state: Optional[ITPState] = None):
        return output.command

    def solve(
        self,
        state: ITPState,
        agent: EvalAgent,
        client: EvalClient,
        ignore_duplicate_inputs: bool = False,
    ) -> Tuple[bool, List[str], SearchSummary]:
        proved = False
        query_count = 0
        timeout_count = 0
        all_input_strings = set()
        pqueue = [SNode(0.0, [], state)]
        itp_running_time = 0.0
        agent_query_time = 0.0
        final_proof_steps = []
        total_time = 0.0
        generated_num = 0
        succeeded_num = 0
        time_before_solving = time.time()
        self.logger.info(f"Start solving in state {state.state_id}")
        self.logger.info(f"State:\n{state.result}\n{state.state}")

        while (
            len(pqueue) > 0
            and query_count < self.query_limit
            and timeout_count < self.step_timeout_limit
        ):
            total_time = time.time() - time_before_solving

            if proved or total_time > self.total_timeout:
                break

            current_node: SNode = heapq.heappop(pqueue)

            input_string = self.make_input(current_node.state)
            if ignore_duplicate_inputs and input_string in all_input_strings:
                continue

            all_input_strings.add(input_string)
            query_count += 1
            self.logger.info(f"[QUERY-{query_count}] {input_string}")

            time_before_query = time.time()
            outputs = agent.query(input_string, self.gen_length)
            agent_query_time += time.time() - time_before_query

            # execute the commands in ITP
            time_before_running = time.time()
            filtered_outputs = self.filter_agent_outputs(outputs)
            ordered_outputs = sorted(
                filtered_outputs, key=lambda x: x.logit, reverse=True
            )
            self.logger.info(
                f"[OUTPUTS-{query_count}] {len(ordered_outputs)} / {len(outputs)} unique commands"
            )

            itp_states = client.execute_many(
                current_node.state_id,
                [output.command.strip() for output in ordered_outputs],
                int(self.step_timeout),
            )
            itp_running_time += time.time() - time_before_running

            # add new nodes to the queue
            for itp_state, output in zip(itp_states, ordered_outputs):
                command = self.get_command(output, itp_state)
                proof_steps = current_node.proof_steps + [command]
                generated_num += 1
                self.logger.info(
                    f"[{itp_state.result}-CMD] {output.logit:.6f} {command}"
                )
                self.logger.info(
                    f"[{itp_state.result}-INFO] {itp_state.logging_info()}"
                )

                if itp_state.result == "SUCCESS":
                    succeeded_num += 1
                    if itp_state.proof_is_finished():
                        proved = True
                        final_proof_steps = proof_steps
                        client.clear_and_rename_state(itp_state.state_id, state.state_id)
                        break
                else:
                    if itp_state.result == "TIMEOUT":
                        timeout_count += 1
                    client.remove_state(itp_state.state_id)
                    continue

                heapq.heappush(
                    pqueue,
                    SNode(
                        current_node.score - output.logit,
                        proof_steps,
                        itp_state,
                    ),
                )

                if len(pqueue) > self.queue_length:
                    max_score_idx = max(
                        range(len(pqueue)), key=lambda i: pqueue[i].score
                    )
                    self.logger.info(
                        f"[DROPPING] {pqueue[max_score_idx].state.state_id}"
                    )
                    del pqueue[max_score_idx]
                    heapq.heapify(pqueue)
                    assert len(pqueue) == self.queue_length

        search_summary = SearchSummary(
            succeeded_num=succeeded_num,
            generated_num=generated_num,
            query_count=query_count,
            timeout_count=timeout_count,
            itp_running_time=itp_running_time,
            agent_query_time=agent_query_time,
            total_time=time.time() - time_before_solving,
        )

        if proved:
            separator = "\n\t"
            self.logger.info(f"[PROVED] {search_summary}")
            self.logger.info(f"[PROOF]{separator + separator.join(final_proof_steps)}")
            return True, final_proof_steps, search_summary

        if not pqueue:
            search_summary.failure_reason = "empty queue"
        elif query_count >= self.query_limit:
            search_summary.failure_reason = "query limit reached"
        elif timeout_count >= self.step_timeout_limit:
            search_summary.failure_reason = "step timeout limit reached"
        elif total_time > self.total_timeout:
            search_summary.failure_reason = "timeout"
        else:
            search_summary.failure_reason = "unknown reason"
        client.clear_and_rename_state(state.state_id, state.state_id)

        self.logger.info(f"[FAILED] {search_summary}")

        return False, [], search_summary


class IsaBestFirstSearch(BestFirstSearch):
    @staticmethod
    def make_input(isa_state: IsaState) -> str:
        return isa_state.state

    @staticmethod
    def get_command(output: EvalAgentOutput, state: Optional[ITPState] = None):
        return (
            output.command
            if output.command.strip().lower() != "sledgehammer"
            else state.message
        )


if __name__ == "__main__":
    import random
    from pathlib import Path

    from agent import EvalAgent, EvalAgentOutput
    from client import IsaEvalClient, IsaState, IsaSetup

    class SimpleAgent(EvalAgent):
        def query(self, state: str, gen_length: int) -> List[EvalAgentOutput]:
            return [
                EvalAgentOutput("by simp", random.random()) for _ in range(gen_length)
            ]

    def test():
        agent = SimpleAgent()
        client = IsaEvalClient(8980)
        setup = IsaSetup(
            isa_path=Path("/home/xiaokun/opt/Isabelle2023"),
            session="Completeness",
            working_directory=Path("/home1/afp-repo/afp-2023/thys/Completeness"),
            session_roots=Path("/home1/afp-repo/afp-2023/thys"),
        )
        client.setup_itp(setup)

        bfs = IsaBestFirstSearch()
        state = client.proceed_until(
            Path("/home1/afp-repo/afp-2023/thys/Completeness/Completeness.thy"),
            'lemma finite_subs: "finite (subs gamma)"',
            10,
        )
        bfs.solve(state, agent, client)

    test()
