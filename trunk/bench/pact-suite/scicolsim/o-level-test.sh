#!/bin/bash
SCS=~/ExM/scicolsim.git/

n=0
while read olevel; do
  n=$(($n+1))
  echo $n: $olevel
  olevels[$n]=$olevel
done < o-levels.txt


#set -x
for i in `seq $n`; do
  olevel=${olevels[$i]}
  echo $i: ${olevel}
  TCL=annealing-exm.olevel$i.tcl
  stc $olevel -C annealing-exm.olevel$i.ic -I $SCS/src $SCS/src/annealing-exm.swift $TCL 

  export TURBINE_USER_LIB=$SCS 
  export TURBINE_DEBUG=0
  export ADLB_DEBUG=1
  export TURBINE_LOG=0
  turbine -n6 $TCL \
              --graph_file=movie_graph.txt \
              --annealingcycles=25 \
              --evoreruns=100 --reruns_per_task=1 \
              --minrange=58 --maxrange=58 \
              --n_epochs=1 --n_steps=1 | ../scripts/opcounts.py | tee annealing-exm.olevel$i.counts
  i=$(($i+1))
done < o-levels.txt
