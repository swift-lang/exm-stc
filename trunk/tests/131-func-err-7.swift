import io;
// THIS-TEST-SHOULD-NOT-COMPILE
main {
  // Check that can't define conflicting variable
  int trace = 1;

  printf("%d", trace);
}
