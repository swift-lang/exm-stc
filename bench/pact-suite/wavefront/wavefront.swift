
/*
   Wavefront pattern
*/

#include <builtins.swift>
#include <io.swift>
#include <mpe.swift>
#include <string.swift>
#include <sys.swift>

main
{
  printf("WAVEFRONT");
  int N = toint(argv("N"));
  float A[][];

  metadata(sprintf("N: %i", N));

  A[0][0] = 0;
  foreach i in [1:N-1]
  {
    A[i][0] = itof(i);
    A[0][i] = itof(i);
  }

  foreach i in [1:N-1]
  {
    foreach j in [1:N-1]
    {
      {
        A[i][j] = f(A[i-1][j-1],A[i-1][j],A[i][j-1]);
      }
    }
  }
  printf("result N: %i value: %.0f", N, A[N-1][N-1]);

}

(float r) f(float a, float b, float c)
{
  // TODO: work function?
  r = a + b + c;
}