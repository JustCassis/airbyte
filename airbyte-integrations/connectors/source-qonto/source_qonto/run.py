#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#


import sys

from airbyte_cdk.entrypoint import launch
from source_qonto import SourceQonto


def run():
    source = SourceQonto()
    launch(source, sys.argv[1:])
