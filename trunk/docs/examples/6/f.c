
#include <math.h>
#include <stdio.h>
#include <mpi.h>
#include "f.h"

// SNIPPET 1
double
f(MPI_Comm comm, int k)
{
  int task_rank, task_size;
  MPI_Comm_rank(comm, &task_rank);
  MPI_Comm_size(comm, &task_size);
  MPI_Barrier(comm);
  sleep(task_rank);
  MPI_Barrier(comm);
  if (task_rank == 0)
    // Return a real value
    return sin(k+task_size);
  // Return a placeholder
  return 0.0;
}
// SNIPPET END
