
namespace eval thfribo_main {

    proc thfribo_main_wrap { rc A } {
        deeprule $A 1 0 "thfribo_main::thfribo_main_wrap_impl $rc $A"
    }

    proc thfribo_main_wrap_impl { rc A } {

        set length [ adlb::container_size $A ]
        set tds [ adlb::enumerate $A dict all 0 ]
        set argv [ list ]
        # Fill argv with blanks
        dict for { i td } $tds {
            lappend argv 0
        }
        # Set values at ordered list positions
        dict for { i td } $tds {
            set s [ adlb::retrieve $td ]
            lset argv $i $s
        }
        set rc_value [ thfribo_main_extension {*}$argv ]
        turbine::store_integer $rc $rc_value
    }
}