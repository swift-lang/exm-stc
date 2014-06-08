# Copyright 2013 University of Chicago and Argonne National Laboratory
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License

#PBS -N Swift
#PBS -q normal
#PBS -l walltime=2:00:00

### Set the job size using appropriate directives for this system
### Blue Waters mode
#PBS -l nodes=8192:ppn=32
### End job size directives selection

#PBS -o ${PBS_JOBID}.pbs.out
# Merge stdout/stderr
#PBS -j oe
# Disable mail
#PBS -m n

set -x

. /opt/modules/default/init/bash
module unload PrgEnv-cray
module load PrgEnv-gnu

# Set variables required for turbine-config.sh
EXM_HOME=/u/sciteam/tarmstro/soft/exm-sc14/v1
#EXM_HOME=/u/sciteam/tarmstro/soft/exm-sc14/v1-debug
export TURBINE_HOME=${EXM_HOME}/turbine
TURBINE_STATIC_EXEC=0
EXEC_SCRIPT=1

# Setup configuration for turbine
source ${TURBINE_HOME}/scripts/turbine-config.sh
BENCH_SRC=/mnt/a/u/sciteam/tarmstro/exm.sfw.git/stc/branches/issue-586/bench/pact-suite/embarrassing
SCRIPT=${BENCH_SRC}/embarrassing_lognorm_tcl
ADLB_PROG=${BENCH_SRC}/embarrassing_lognorm

# Put output in directory job was launched from
TURBINE_OUTPUT=${PBS_O_WORKDIR}

export TURBINE_USER_LIB=
export TURBINE_LOG=0
export ADLB_PERF_COUNTERS=1
export ADLB_PRINT_TIME=1

# For adlb version
export CONTROL_RATIO=8

# Round-robin placement to ensure servers distributed nicely
export MPICH_RANK_REORDER_METHOD=0

# Output header
echo "Turbine: turbine-aprun.sh"
date "+%m/%d/%Y %I:%M%p"
echo

NODES=${PBS_NUM_NODES}
PPN=${PBS_NUM_PPN}
PROCS=$((NODES * PPN))

# Log the parameters
echo "TURBINE_HOME: ${TURBINE_HOME}"
echo "SCRIPT:       ${SCRIPT}"
echo "ADLB_PROG:    ${ADLB_PROG}"
echo "PROCS:        ${PROCS}"
echo "NODES:        ${NODES}"
echo "PPN:          ${PPN}"

# Record the script
cp $0 ${TURBINE_OUTPUT}/${PBS_JOBID}.submit

# Be sure we are in an accessible directory
cd ${TURBINE_OUTPUT}

OPT_LEVELS="adlb O3 O2 O1 O0"


for trial in 1 2 3
do
  for opt in ${OPT_LEVELS}
  do
    APRUN_NODES=$NODES

    while ((APRUN_NODES > 0))
    do
      APRUN_PROCS=$((APRUN_NODES*PPN))
      sigma=1

      # Params, assuming sigma=1
      # Aim for a few minutes long job
      # mu=-8.515 # 0.2ms
      #SCALE_FACTOR=5
      #mu=-7.60 # 0.5ms
      #SCALE_FACTOR=2
      mu=-6.905 # 1ms
      SCALE_FACTOR=1
      
      # ADLB partitions outer loop, ensure plenty of iterations to be fair
      N=25000
      #N=100
      M=$((APRUN_PROCS*5*SCALE_FACTOR))
      if [ $opt = adlb ]
      then
        ARGS="${N} ${M} ${mu} ${sigma}"
        PROG=${ADLB_PROG}
      else
        ARGS="--N=${N} --M=${M} --mu=${mu} --sigma=${sigma}"
        PROG=${SCRIPT}.${opt}
      fi

      export ADLB_SERVERS=${APRUN_NODES}
      echo
      echo "Run ${PROG} with args: ${ARGS}"
      echo "ADLB_SERVERS:    ${ADLB_SERVERS}"
      echo "APRUN_NODES:     ${APRUN_NODES}"
      echo "APRUN_PROCS:     ${APRUN_PROCS}"
      aprun -n ${APRUN_PROCS} -N ${PPN} -cc none -d 1 ${TCLSH} ${PROG} ${ARGS} \
              > "${PBS_JOBID}.${opt}.p${APRUN_PROCS}.${trial}.aprun.out" 2>&1 &

      if (( APRUN_NODES == NODES ))
      then
        # Don't have any free nodes
        wait
      fi

      # Launch smaller sized jobs in powers of two
      # The remainder will add up to one less than the total number of
      # nodes allocated, so that the remainder will fit in the largest
      # job's allocate
      APRUN_NODES=$((APRUN_NODES/2))
      # APRUN_NODES=0
    done

    # Wait for jobs for this opt level
    wait
  done
done
