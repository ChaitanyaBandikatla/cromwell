#!/bin/bash

set -e

CROMWELL_DIR=$1
cd $CROMWELL_DIR
sbt --warn assembly
CROMWELL_JAR=$(find target | grep 'cromwell.*\.jar')
mv $CROMWELL_JAR ./cromwell.jar
sbt --warn clean
