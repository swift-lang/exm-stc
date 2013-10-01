import assert;
import math;


(int r) i(int x) {
    r = x;
}

(float r) f(float x) {
    r = x;
}

main {
    assertEqual(itof(3) / itof(2), 1.5, "Check itofconversion");
    assertEqual(round(2.4), 2, "check round 1");
    assertEqual(round(2.6), 3, "check round 2");
    assertEqual(floor(2.6), 2, "check floor");
    assertEqual(ceil(2.6), 3, "check ceil");
   

    // Set it up so that the optimizer will use 
    //  the local version of the operations so it can test that
    int a = i(3);
    int b = i(2);
    wait (a, b) {
        assertEqual(itof(a) / itof(b), 1.5, "local Check itofconversion");
    }

    float c = f(2.4);
    float d = f(2.6);
    wait (c, d) {
        assertEqual(round(c), 2, "local check round 1");
        assertEqual(round(d), 3, "local check round 2");
        assertEqual(floor(d), 2, "local check floor");
        assertEqual(ceil(d), 3, "local check ceil");
    }
}
