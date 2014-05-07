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
#PBS -l walltime=0:30:00

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
EXM_HOME=/u/sciteam/tarmstro/soft/exm-sc14/v2-opt
#EXM_HOME=/u/sciteam/tarmstro/soft/exm-sc14/v1-debug
export TURBINE_HOME=${EXM_HOME}/turbine
TURBINE_STATIC_EXEC=0
EXEC_SCRIPT=1

# Setup configuration for turbine
source ${TURBINE_HOME}/scripts/turbine-config.sh
BENCH_SRC=/mnt/a/u/sciteam/tarmstro/exm.sfw.git/stc/branches/issue-586/bench/pact-suite/uts
SCRIPT=${BENCH_SRC}/uts_tcl
ADLB_PROG=${BENCH_SRC}/uts_adlb

# Put output in directory job was launched from
TURBINE_OUTPUT=${PBS_O_WORKDIR}

export TURBINE_USER_LIB=
export TURBINE_LOG=0
export ADLB_PERF_COUNTERS=1
export ADLB_PRINT_TIME=1

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


for opt in ${OPT_LEVELS}
do
  for trial in 1
  do
    APRUN_NODES=$NODES
    GEN_MX=44

    while ((APRUN_NODES > 0))
    do
      APRUN_PROCS=$((APRUN_NODES*PPN))
      
      # GEO/LINEAR was hardcoded into programs
      b_0=4
      max_nodes=128
      max_steps=1000000
      root_id=42

      if [ $opt = adlb ]
      then
        ARGS="--gen_mx ${GEN_MX} --b_0 ${b_0} --max_nodes ${max_nodes} \
              --max_steps ${max_steps} --root_id ${root_id}"
        PROG=${ADLB_PROG}
      else
        ARGS="--gen_mx=${GEN_MX} --b_0=${b_0} --max_nodes=${max_nodes} \
              --max_steps=${max_steps} --root_id=${root_id}"
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
      GEN_MX=$((GEN_MX - 1)) # Scale down
    done

    # Wait for jobs for this opt level
    wait
  done
done
