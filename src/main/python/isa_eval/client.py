from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, List, Optional

import grpc

import isa_eval_pb2
import isa_eval_pb2_grpc


ISA_PROOF_COMMANDS = [
    "lemma",
    "theorem",
    "corollary",
    "proposition",
    "schematic_goal",
    "interpretation",
    "global_interpretation",
    "sublocale",
    "instance",
    "notepad",
    "function",
    "termination",
    "specification",
    "old_rep_datatype",
    "typedef",
    "functor",
    "quotient_type",
    "lift_definition",
    "quotient_definition",
    "bnf",
    "subclass",
]


def make_setup(
    isa_path: Path, session: str, working_directory: Path, session_roots: Optional[Path]
):
    return isa_eval_pb2.Setup(
        isa_path=str(isa_path),
        session=session,
        working_directory=str(working_directory),
        session_roots=str(session_roots) if session_roots is not None else "",
    )


def make_theory_content(theory: Path, content: str, timeout: int):
    return isa_eval_pb2.TheoryContent(
        theory=str(theory), content=content, timeout=timeout
    )


def make_sledgehammer_request(state_id: str, timeout: int, sledgehammer_timeout: int):
    return isa_eval_pb2.SledgehammerRequest(id=state_id, timeout=timeout, sledgehammer_timeout=sledgehammer_timeout)


def make_proof_commands(state_id: str, commands: str, timeout: int):
    return isa_eval_pb2.ProofCommands(id=state_id, commands=commands, timeout=timeout)


def make_state_request(state_id: str):
    return isa_eval_pb2.StateRequest(id=state_id)


def make_clear_and_rename_request(state_id: str, new_state_id: str):
    return isa_eval_pb2.ClearAndRenameRequest(id=state_id, new_id=new_state_id)


def make_parse_request(
    theory: Path, only_statements: bool = False, remove_ignored: bool = True
):
    return isa_eval_pb2.ParseRequest(
        theory=str(theory),
        only_statements=only_statements,
        remove_ignored=remove_ignored,
    )


def make_outcome_state(outcome_state: isa_eval_pb2.OutcomeState):
    return IsaState(
        state_id=outcome_state.id,
        result=outcome_state.result,
        message=outcome_state.message,
        proof_level=outcome_state.level,
        state=outcome_state.state,
    )


def make_isa_state_recursive(x: Any):
    if isinstance(x, isa_eval_pb2.OutcomeState):
        return IsaState(
            state_id=x.id,
            result=x.result,
            message=x.message,
            proof_level=x.level,
            state=x.state,
        )
    elif isinstance(x, list):
        return [make_isa_state_recursive(y) for y in x]
    elif isinstance(x, tuple):
        return tuple(make_isa_state_recursive(y) for y in x)
    elif isinstance(x, dict):
        return {
            make_isa_state_recursive(k): make_isa_state_recursive(v)
            for k, v in x.items()
        }
    elif isinstance(x, set):
        return {make_isa_state_recursive(y) for y in x}
    elif isinstance(x, Iterable):
        return (make_isa_state_recursive(y) for y in x)
    return x


def return_isa_state(call):
    def inner(*args, **kwargs):
        return make_isa_state_recursive(call(*args, **kwargs))

    return inner


class ITPSetup:
    pass


@dataclass
class ITPState:
    state_id: str
    result: str
    message: str
    proof_level: int
    state: str

    def proof_is_finished(self) -> bool:
        return self.proof_level == 0

    def logging_info(self) -> str:
        return (self.state if self.result == "SUCCESS" else self.message).replace(
            "\n", " "
        )


@dataclass
class ITPCommand:
    command: str
    name: str
    line: int


class EvalClient:
    def __init__(self, port: int) -> None:
        self.port = port
        self.stub: Optional[Any] = None

    def open_stub(self) -> None:
        pass

    def setup_itp(self, setup: ITPSetup) -> ITPSetup:
        pass

    def close_itp(self) -> None:
        pass

    def proceed_until(self, thy_path: Path, content: str, timeout: int) -> ITPState:
        pass

    def execute(self, state_id: str, commands: str, timeout: int) -> ITPState:
        pass

    def execute_many(
        self, state_id: str, commands_lst: List[str], timeout: int
    ) -> List[ITPState]:
        pass

    def clone_state(self, state_id: str) -> ITPState:
        pass

    def remove_state(self, state_id: str) -> None:
        pass

    def clear_and_rename_state(self, state_id: str, new_state_id: str) -> ITPState:
        pass

    def get_theory_commands(
        self, thy_path: Path, only_statements: bool, remove_ignored: bool
    ) -> List[ITPCommand]:
        pass


@dataclass
class IsaSetup(ITPSetup):
    isa_path: Path
    session: str
    working_directory: Path
    session_roots: Optional[Path]


class IsaState(ITPState):
    pass


class IsaCommand(ITPCommand):
    pass


class IsaEvalClient(EvalClient):
    def __init__(self, port: int):
        super().__init__(port)
        self.stub: Optional[isa_eval_pb2_grpc.IsaEvalStub] = None

    def _check_stub(self):
        assert self.stub is not None, "stub is not initialized"

    def open_stub(self):
        if self.stub is None:
            self.stub = isa_eval_pb2_grpc.IsaEvalStub(
                grpc.insecure_channel(f"localhost:{self.port}")
            )

    def setup_itp(self, setup: IsaSetup):
        self.open_stub()
        return self.stub.SetupIsabelle(
            make_setup(
                setup.isa_path,
                setup.session,
                setup.working_directory,
                setup.session_roots,
            )
        )

    def close_itp(self) -> None:
        if self.stub is not None:
            self.stub.CloseIsabelle(isa_eval_pb2.Empty())
            self.stub = None

    @return_isa_state
    def proceed_until(self, thy_path: Path, content: str, timeout: int) -> IsaState:
        self._check_stub()
        return self.stub.ProceedUntil(make_theory_content(thy_path, content, timeout))

    @return_isa_state
    def execute(self, state_id: str, commands: str, timeout: int) -> IsaState:
        self._check_stub()
        if commands.strip().lower() == "sledgehammer":
            return self.stub.CallSledgehammer(
                make_sledgehammer_request(state_id, timeout, sledgehammer_timeout=timeout * 3)
            )
        return self.stub.Execute(make_proof_commands(state_id, commands, timeout))

    def execute_many(
        self, state_id: str, commands_lst: List[str], timeout: int
    ) -> List[IsaState]:
        self._check_stub()
        normal_proof_commands = [
            make_proof_commands(state_id, cmd, timeout)
            for cmd in commands_lst
            if cmd != "sledgehammer"
        ]
        if len(normal_proof_commands) == 0:
            outputs = []
        else:
            outputs = list(make_isa_state_recursive(self.stub.ExecuteMany(iter(normal_proof_commands))))

        try:
            if (idx := commands_lst.index("sledgehammer")) != -1:
                sledgehammer_request = make_sledgehammer_request(state_id, timeout, timeout * 3)
                extra_output = self.stub.CallSledgehammer(sledgehammer_request)
                outputs.insert(idx, make_isa_state_recursive(extra_output))
        except ValueError as _:
            pass

        return outputs

    @return_isa_state
    def clone_state(self, state_id: str) -> IsaState:
        self._check_stub()
        return self.stub.CloneState(make_state_request(state_id))

    def remove_state(self, state_id: str) -> None:
        self._check_stub()
        self.stub.RemoveState(make_state_request(state_id))

    @return_isa_state
    def clear_and_rename_state(self, state_id: str, new_state_id: str) -> IsaState:
        self._check_stub()
        return self.stub.ClearAndRename(
            make_clear_and_rename_request(state_id, new_state_id)
        )

    @return_isa_state
    def get_theory_commands(
        self, thy_path: Path, only_statements: bool, remove_ignored: bool
    ) -> List[IsaCommand]:
        self._check_stub()
        isa_cmd_iterator = self.stub.GetTheoryCommands(
            make_parse_request(thy_path, only_statements, remove_ignored)
        )
        return list(isa_cmd_iterator)

    @return_isa_state
    def call_sledgehammer(self, state_id: str, timeout: int, sledgehammer_timeout: int) -> IsaState:
        self._check_stub()
        return self.stub.CallSledgehammer(make_sledgehammer_request(state_id, timeout, sledgehammer_timeout))


def run():
    isa_path = Path("/home/xiaokun/opt/Isabelle2023")
    session = "Completeness"
    working_directory = Path("/home1/afp-repo/afp-2023/thys/Completeness")
    session_roots = Path("/home1/afp-repo/afp-2023/thys")

    test_theory_path = Path(
        "/home/xiaokun/projects/isa-eval/src/main/resources/Test.thy"
    )

    with grpc.insecure_channel("localhost:8980") as channel:
        stub = isa_eval_pb2_grpc.IsaEvalStub(channel)

        request1 = make_setup(isa_path, session, working_directory, session_roots)
        response1 = stub.SetupIsabelle(request1)
        print(response1)

        request2 = make_theory_content(
            test_theory_path, 'lemma test: "p ==> q ==> p"', 10
        )
        response2 = stub.ProceedUntil(request2)
        print(response2)

        request3 = make_sledgehammer_request(response2.id, 10, 30)
        response3 = stub.CallSledgehammer(request3)
        print(response3)

        request4 = make_proof_commands("default", "qed", 10)
        response4 = stub.Execute(request4)
        print(response4)


if __name__ == "__main__":
    run()
