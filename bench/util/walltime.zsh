
# Analyze output

# Assumes the presence of:
checkvars START STOP
checkvars OUTPUT OUTPUT_DIR

TOOK=$(( STOP - START ))
print "TOOK: ${TOOK}"

# Start processing output

float -F 3 TIME TOTAL_TIME TOTAL_RATE WORKER_RATE UTIL

if grep -qi abort ${OUTPUT}
then
  print "run aborted!"
  return 1
fi
TIME=$( turbine_stats_walltime ${OUTPUT} )
if [[ ${TIME} == "" ]]
then
  print "run failed!"
  return 1
fi

# Collect stats:
{
  TIME=$(( TIME - ADLB_EXHAUST_TIME ))
  print "N: ${N}"
  print "TIME: ${TIME}"
  if (( TIME ))
  then
    TOTAL_RATE=$(( N / TIME ))
    print "TOTAL_RATE: ${TOTAL_RATE}"
    WORKER_RATE=$(( N / TIME / TURBINE_WORKERS ))
    print "WORKER_RATE: ${WORKER_RATE}"
  fi
  if (( DELAY ))
  then
    WORK_TIME=$(( N * DELAY/1000 ))
    TOTAL_TIME=$(( TIME * TURBINE_WORKERS ))
    UTIL=$(( WORK_TIME / TOTAL_TIME ))
    print "UTIL: ${UTIL}"
  fi
} | tee ${OUTPUT_DIR}/stats.txt

return 0
