#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#


import sys

from airbyte_cdk.entrypoint import launch
from source_search_metrics import SourceSearchMetrics


def run():
    source = SourceSearchMetrics()
    launch(source, sys.argv[1:])
