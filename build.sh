#!/bin/bash
PYTHON_SRC=src/main/python/isa_eval

sbt compile

python -m grpc_tools.protoc -I src/main/protobuf --python_out=$PYTHON_SRC --pyi_out=$PYTHON_SRC --grpc_python_out=$PYTHON_SRC src/main/protobuf/isa_eval.proto
