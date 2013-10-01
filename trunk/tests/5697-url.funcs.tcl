
package provide funcs_5697 0.5

namespace eval funcs_5697 {
  package require turbine 0.3.0
  namespace import ::turbine::*

  proc copy_url5 { outputs inputs } {
    puts [ list Entering copy_url5 $outputs $inputs ]
    set outurl [ lindex $outputs 0 ]
    set inurl [ lindex $inputs 0 ]

    rule [ list [ get_file_status $inurl ] [ get_file_path $outurl ] ] \
         [ list funcs_5697::copy_url5_body $outurl $inurl ]
  }

  proc copy_url5_body { outurl inurl  } {
    puts [ list Entering copy_url5_body $outurl $inurl ]
    read_refcount_decr [ get_file_status $inurl ]
    set outpath [ retrieve_decr_string [ get_file_path $outurl ] ]
    set inpath [ retrieve_decr_string [ get_file_path $inurl ] ]

    copy_url5_impl $inpath $outpath
    store_void [ get_file_status $outurl ]
  }

  proc copy_url5_impl { inpath outpath } {
    puts [ list Entering copy_url5_impl $outpath $inpath ]
    puts [ list copy_url5 $inpath $outpath ]
  }
}
