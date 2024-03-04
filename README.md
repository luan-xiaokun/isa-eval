# Isa-Eval: Isabelle Proving Agent Evaluation

Isa-Eval is a framework for evaluating Isabelle proving agents.
A server is implemented in Scala to interact with underlying Isabelle process, and a client is implemented in Python
to communicate with the server through gRpc and expose the API to the agent.
A simple best-first search (BFS) is shipped with the server and client, which is described in
[GPT-f](https://arxiv.org/abs/2009.03393) and [Thor](https://arxiv.org/abs/2205.10893).

Some of the code is based on the [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle) project.


## Requirements

### 1. Isabelle

Isa-Eval is mainly developed for Isabelle2023, the server is known to be compatible with Isabelle2021, Isabelle2021-1,
and Isabelle2022. It is the duty of the user to make sure the Isabelle version is compatible with the evaluated theory
files.

**Optional**: If the evaluation benchmark relies on [Archive of Formal Proofs (AFP)](https://www.isa-afp.org/), make
sure first to build heap images for AFP theories, for example:

```shell
# build with 4 parallel jobs
path/to/isabelle/binary build -b -D path/to/afp/thys -j 4
```

Note that the building process may take a long time, and it is very memory-consuming (4 jobs may require 32 GB of RAM).
The built heap images are usually stored in `~/.isabelle/Isabelle2023/heaps`. For more information, please check the
[manual](https://isabelle.in.tum.de/doc/system.pdf) of Isabelle.

### 2. Scala

We recommend building and running Isa-Eval with JAVA 11, Scala 2.13.12 and sbt.

```shell
# we recommend using sdkman to install and setup Scala
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 11.0.21-tem
sdk install sbt
```

### 3. Python

Isa-Eval is developed using Python 3.10.13, but it should be compatible with most Python 3 versions.
Requirements can be installed using pip:

```shell
pip install -r requirements.txt
```


## Usage

### 1. Compile

Before compilation, make sure that the port 8980 is available. If not, you can change the port in
`src/main/scala/isa_eval/IsaEvalServer.scala` (and don't forget to change the port in the Python client as well).

```scala
object IsaEvalServer extends ServerMain {
  // you can change the port here
  override def port: Int = 8980
  // ...
}
```

First compile the server with sbt:

```shell
# this may take a while when running for the first time
sbt
# then run 'compile' in the sbt shell, i.e.
# sbt:isa-eval> compile
compile
```

Then generate the Python client code:

```shell
# make sure you are in the root directory of the project
cd prise
python -m grpc_tools.protoc -I src/main/protobuf --python_out=src/main/python/isa_eval \
       --pyi_out=src/main/python/isa_eval --grpc_python_out=src/main/python/isa_eval \
       src/main/protobuf/isa_eval.proto
```

Alternatively, you can just run `./build.sh` for the above two steps.

### 2. Run the server

Then start the server:

```shell
sbt run
```

### 3. Evaluate

Finally, wrap your agent by inheriting the `EvalAgent` class and implement the `query` method (and also overriding the
`make_input` method for the `IsaBestFirstSearch` class). Then the evaluation  can be done by calling the
`evaluate_isabelle_agent` function.

```python
import random

from agent import EvalAgent, EvalAgentOutput
from search import IsaBestFirstSearch
from evaluate import evaluate_isabelle_agent


class SimpleAgent(EvalAgent):
    def query(self, state: str, gen_length: int):
        return [
            EvalAgentOutput("by simp", random.random()) for _ in range(gen_length)
        ]


eval_records, times_dict = evaluate_isabelle_agent(
    isa_path="/path/to/your/Isabelle2023",
    theories_path="/path/to/evaluation/benchmark",
    agent=SimpleAgent(),
    solver=IsaBestFirstSearch(),
    session_roots=None,
    port=8980,
)
```

The evaluation results can be pretty printed by using `pretty_print_eval_summary`:

```python
pretty_print_eval_summary(eval_records, times_dict)

# pretty print results look like:
# Solved 15 out of 248 lemmas
# Generated 248 commands, succeeded 15
# Total query count: 248, timeout count: 3
# Average evaluation time (each file): 1.1693 seconds
# Average generated proof length: 1.0000
# Average search time: 0.1433 seconds (query 0.0000 / ITP 0.1427)
```