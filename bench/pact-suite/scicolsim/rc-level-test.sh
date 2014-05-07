#!/bin/bash
SCS=~/ExM/scicolsim.git/

export STC_FLAGS="-T no-engine -I $SCS/src"

export TURBINE_USER_LIB=$SCS 

# FROM PACT'13 PAPER
#ARGS='--graph_file=movie_graph.txt \
#            --annealingcycles=25 \
#            --evoreruns=100 --reruns_per_task=1 \
#            --minrange=58 --maxrange=58 \
#            --n_epochs=1 --n_steps=1'

# CCGrid '13 draft
#ARGS="--graph_file=${SCS}/data/movie_graph.txt \
#            --annealingcycles=25 \
#            --evoreruns=100 --reruns_per_task=1 \
#            --minrange=58 --maxrange=108 --rangeinc=50 \
#            --n_epochs=1 --n_steps=1"

ARGS="--graph_file=${SCS}/data/movie_graph.txt \
            --annealingcycles=2 \
            --evoreruns=100 --reruns_per_task=1 \
            --minrange=58 --maxrange=108 --rangeinc=50 \
            --n_epochs=30 --n_steps=50"

export ARGS

../scripts/o-level-test.sh $SCS/src/annealing-exm.swift ./rc-o-levels.txt ./rc-o-levels-out
