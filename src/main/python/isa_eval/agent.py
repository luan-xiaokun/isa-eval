from dataclasses import dataclass
from typing import List


@dataclass
class EvalAgentOutput:
    command: str
    logit: float = float("inf")


class EvalAgent:
    def query(self, state: str, gen_length: int) -> List[EvalAgentOutput]:
        pass

    def query_batch(self, states: List[str], gen_length: int) -> List[List[EvalAgentOutput]]:
        pass
