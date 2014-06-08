#!/bin/bash

set -e

INST=$HOME/ExM/inst
TURBINE=$INST/turbine
LB=$INST/lb
CUTILS=$INST/c-utils
STC=$INST/stc

source $TURBINE/scripts/turbine-build-config.sh

CC=mpicc
CFLAGS="-std=c99 -Wall -O2 ${TURBINE_INCLUDES} -I ../util"
LDFLAGS=""
LIBS="${TURBINE_LIBS} ${TURBINE_RPATH}"

MKSTATIC=$TURBINE/scripts/mkstatic/mkstatic.tcl

#${CC} ${CFLAGS} ${LDFLAGS} ensemble.c ${LIBS} -o embarrassing 

STC=$INST/stc/bin/stc

for OPT in 0 1 2 3
do
  STC_OPTLEVEL="-O$OPT"
  STC_FLAGS="$STC_OPTLEVEL"
  STC_FLAGS+=" -T no-engine"
  
  echo -n O$OPT
  PREFIX=ensemble
  PREFIX_OPT=${PREFIX}.O${OPT}
  PREFIX_TCL=${PREFIX}_tcl.O${OPT}
  ${STC} ${STC_FLAGS} -C ${PREFIX_OPT}.ic ${PREFIX}.swift ${PREFIX_OPT}.tcl
  echo -n .
  ${MKSTATIC} ${PREFIX}.manifest --main-script ${PREFIX_OPT}.tcl -c ${PREFIX_TCL}.c
  echo -n .

  ${CC} ${CFLAGS} ${LDFLAGS} ${PREFIX_TCL}.c \
    $(${MKSTATIC} ${PREFIX}.manifest --link-objs --link-flags) \
    ${LIBS} -o ${PREFIX_TCL}
  echo -n

done


echo "OK."
