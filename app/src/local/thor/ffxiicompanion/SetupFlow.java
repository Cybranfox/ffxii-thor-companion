package local.thor.ffxiicompanion;

import java.util.*;

/**
 * 0.16.0: the setup screen's five ordered checks (storage, setup files, loaders, game script, game data), as a pure
 * reducer over one input map that SetupInputs fills from what the app observes. Each check is OK, MISSING or UNKNOWN
 * with a fixed message key and one action; no live game means UNKNOWN, never "missing". Ported unchanged from the
 * reviewed draft (draft/setup_flow, Setup_Flow_2026-10-23.md) with its 49 fixture cases (tests/fixtures/setup_flow.json).
 * New-user test (NewUser_Test_2026-10-24.md): no loader receipt ever means "run install.cmd first"; a build can be
 * started while the game is closed once the loader has run this release; a requested build that waits for the game
 * says so; game data that only waits on earlier checks doesn't take the card's one button from them.
 */
public final class SetupFlow {
    public enum State { OK, MISSING, UNKNOWN }
    public enum Action { NONE, GRANT_STORAGE, SYNC_INSTALL, SHOW_SETUP_STEPS, OPEN_GAME_NATIVE,
        RESTART_GAME, UPDATE_APP, RESTORE_INSTALL, REINSTALL_BOOTSTRAP, WAIT_FIELD,
        RESUME_BUILD, WAIT_BUILD, RETRY_BUILD, START_BUILD }
    public static final class Step {
        public final String id,messageKey;
        public final State state;
        public final Action action;
        public final boolean durableRepair;
        Step(String id,State state,String key,Action action,boolean repair){this.id=id;this.state=state;messageKey=key;this.action=action;durableRepair=repair;}
    }
    public static final class View {
        public final List<Step> steps;
        public final Step firstBlocker;
        public final boolean complete,needsOnboarding,shouldPersistCompletion;
        View(List<Step> steps,Step first,boolean complete,boolean onboard,boolean persist){this.steps=Collections.unmodifiableList(steps);firstBlocker=first;this.complete=complete;needsOnboarding=onboard;shouldPersistCompletion=persist;}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return o instanceof Map?(Map<String,Object>)o:Collections.emptyMap();}
    static Object at(Map<String,Object> root,String path){Object o=root;for(String p:path.split("\\."))o=map(o).get(p);return o;}
    static String str(Object o){return o instanceof String?(String)o:null;}
    static boolean yes(Object o){return Boolean.TRUE.equals(o);}
    static boolean no(Object o){return Boolean.FALSE.equals(o);}
    static Long number(Object o){if(!(o instanceof Byte||o instanceof Short||o instanceof Integer||o instanceof Long))return null;return((Number)o).longValue();}
    static Step s(String id,State state,String key,Action action,boolean repair){return new Step(id,state,key,action,repair);}
    static boolean hasVersion(Object list,String value){if(!(list instanceof List)||value==null)return false;for(Object x:(List<?>)list)if(value.equals(x))return true;return false;}
    static boolean releaseMatch(Map<String,Object> root){
        Long expected=number(at(root,"expected.sequence")),received=number(at(root,"loaderStatus.sequence")),stub=number(at(root,"loaderStatus.stub")),minimum=number(at(root,"expected.minStub"));
        String version=str(at(root,"expected.version"));
        return expected!=null&&expected>0&&expected.equals(received)&&stub!=null&&minimum!=null&&stub>=minimum&&version!=null&&version.equals(at(root,"loaderStatus.version"));
    }
    static boolean receiptCurrent(Map<String,Object> root){return "ffxii.loader.v1".equals(at(root,"loaderStatus.contract"))&&yes(at(root,"evidence.receiptApplicable"));}
    static boolean liveAccepted(Map<String,Object> root){return "LIVE".equals(at(root,"receiver.transport"))&&yes(at(root,"receiver.accepted"))&&!yes(at(root,"snapshot.synthetic"))&&yes(at(root,"evidence.snapshotGenerationMatches"));}
    static String refusal(Map<String,Object> root){
        if(!receiptCurrent(root)||liveAccepted(root))return null;
        String r=str(at(root,"loaderStatus.result"));return r==null||r.equals("ok")||r.equals("already_loaded")?null:r;
    }
    /** The loader ran this release fine at its last start (receipt), so a build can be queued while the game is closed. */
    static boolean loaderRanOk(Map<String,Object> root){String r=str(at(root,"loaderStatus.result"));return receiptCurrent(root)&&releaseMatch(root)&&("ok".equals(r)||"already_loaded".equals(r));}
    static boolean canBuild(Map<String,Object> r,Step script,Step loaders){return script.state==State.OK&&loaders.state==State.OK||loaderRanOk(r);}
    public static View evaluate(Map<String,Object> input){
        Map<String,Object> root=input==null?Collections.emptyMap():input;
        List<Step> out=new ArrayList<>();Step storage=storage(root);out.add(storage);
        boolean accessible=storage.state==State.OK;
        Step install=accessible?install(root):s("install",State.UNKNOWN,"install_wait_storage",Action.GRANT_STORAGE,false);out.add(install);
        String refusal=accessible?refusal(root):null;
        Step loaders=accessible&&install.state==State.OK?loaders(root,refusal):s("loaders",State.UNKNOWN,"loaders_wait_install",install.state==State.MISSING?Action.SYNC_INSTALL:Action.SHOW_SETUP_STEPS,false);out.add(loaders);
        Step script=accessible&&install.state==State.OK?script(root,refusal):s("script",State.UNKNOWN,"script_wait_install",install.state==State.MISSING?Action.SYNC_INSTALL:Action.SHOW_SETUP_STEPS,false);out.add(script);
        Step data=accessible?data(root,script,loaders):s("data",State.UNKNOWN,"data_wait_storage",Action.GRANT_STORAGE,false);out.add(data);
        Step first=null;boolean all=true,repair=false;
        for(Step x:out){all&=x.state==State.OK;repair|=x.durableRepair;if(first==null&&x.state==State.MISSING)first=x;}
        if(first==null)for(Step x:out)if(x.state==State.UNKNOWN){first=x;break;}
        // Missing game data that can't be built yet waits on the earlier checks: their action comes first.
        if(first==data&&data.action!=Action.START_BUILD&&data.action!=Action.RETRY_BUILD)for(Step x:out)if(x!=data&&x.state==State.UNKNOWN){first=x;break;}
        Long revision=number(at(root,"expected.setupRevision")),done=number(at(root,"history.completedSetupRevision"));boolean latched=revision!=null&&revision>0&&revision.equals(done);
        return new View(out,first,all,!all&&(!latched||repair),all&&!latched);
    }
    static Step storage(Map<String,Object> r){
        if(no(at(r,"platform.supported")))return s("storage",State.MISSING,"platform_unsupported",Action.SHOW_SETUP_STEPS,true);
        if(no(at(r,"platform.storageGranted")))return s("storage",State.MISSING,"storage_permission_needed",Action.GRANT_STORAGE,true);
        if(no(at(r,"platform.sharedReadable"))||no(at(r,"platform.sharedWritable")))return s("storage",State.MISSING,"storage_unavailable",Action.SHOW_SETUP_STEPS,true);
        if(yes(at(r,"platform.supported"))&&yes(at(r,"platform.storageGranted"))&&yes(at(r,"platform.sharedReadable"))&&yes(at(r,"platform.sharedWritable")))return s("storage",State.OK,"storage_ready",Action.NONE,false);
        return s("storage",State.UNKNOWN,"storage_not_checked",Action.SHOW_SETUP_STEPS,false);
    }
    static Step install(Map<String,Object> r){
        if(no(at(r,"install.releaseAssetsComplete")))return s("install",State.MISSING,"install_release_assets_missing",Action.UPDATE_APP,true);
        if(no(at(r,"install.bundleBytesMatch")))return s("install",State.MISSING,"install_sync_needed",Action.SYNC_INSTALL,true);
        if(yes(at(r,"install.releaseAssetsComplete"))&&yes(at(r,"install.bundleBytesMatch")))return s("install",State.OK,"install_files_current",Action.NONE,false);
        return s("install",State.UNKNOWN,"install_not_checked",Action.SYNC_INSTALL,false);
    }
    static Step loaders(Map<String,Object> r,String refusal){
        if("unsupported_lua".equals(refusal))return s("loaders",State.MISSING,"loader_unsupported_lua",Action.REINSTALL_BOOTSTRAP,true);
        // A current inventory is positive evidence only. No receipt/snapshot does not mean a missing DLL.
        boolean current=yes(at(r,"evidence.loaderInventoryCurrent"))&&liveAccepted(r);
        if(current){
            Object raw=at(r,"snapshot.data.hello.mods");if(raw instanceof List)for(Object item:(List<?>)raw){Map<String,Object> mod=map(item);String id=str(mod.get("id"));if(("ffgriever.efl".equals(id)||"ffgriever.lualoader".equals(id))&&no(at(mod,"file_present.value")))return s("loaders",State.MISSING,"loader_file_reported_missing",Action.REINSTALL_BOOTSTRAP,true);}
            String lua=str(at(r,"snapshot.data.hello.loader.lua"));
            Object allowed=at(r,"expected.allowedLuaVersions");
            if(lua!=null&&allowed instanceof List&&!((List<?>)allowed).isEmpty()&&!hasVersion(allowed,lua))return s("loaders",State.MISSING,"loader_version_reported_unsupported",Action.REINSTALL_BOOTSTRAP,true);
            if(no(at(r,"evidence.eflCompatible")))return s("loaders",State.MISSING,"efl_version_reported_unsupported",Action.REINSTALL_BOOTSTRAP,true);
            if(lua!=null&&hasVersion(at(r,"expected.allowedLuaVersions"),lua)&&yes(at(r,"evidence.eflCompatible")))return s("loaders",State.OK,"loaders_reported_ready",Action.NONE,false);
            return s("loaders",State.UNKNOWN,"loader_versions_not_confirmed",Action.SHOW_SETUP_STEPS,false);
        }
        // No loader receipt ever: the loader has never run, so install.cmd is the likely next step (still not "missing").
        if(no(at(r,"evidence.loaderReceiptSeen"))){
            // 0.16.0: install.cmd's own result.txt says how the one-time install went; the game hasn't run it yet.
            String boot=str(at(r,"evidence.bootstrapResult"));
            if("failed".equals(boot))return s("loaders",State.MISSING,"loader_install_failed",Action.SHOW_SETUP_STEPS,true);
            if("installed_warning".equals(boot))return s("loaders",State.UNKNOWN,"loader_installed_warning",Action.OPEN_GAME_NATIVE,false);
            if("installed".equals(boot))return s("loaders",State.UNKNOWN,"loader_installed_start_game",Action.OPEN_GAME_NATIVE,false);
            return s("loaders",State.UNKNOWN,"loader_not_seen",Action.SHOW_SETUP_STEPS,false);
        }
        return s("loaders",State.UNKNOWN,"loaders_not_observed",Action.OPEN_GAME_NATIVE,false);
    }
    static Step script(Map<String,Object> r,String refusal){
        if(refusal!=null){
            switch(refusal){
                case "stub_old":return s("script",State.MISSING,"script_stub_old",Action.REINSTALL_BOOTSTRAP,true);
                case "unsupported_lua":return s("script",State.MISSING,"script_unsupported_lua",Action.REINSTALL_BOOTSTRAP,true);
                case "tampered":case "bad_format":case "missing":return s("script",State.MISSING,"script_restore_bundled_copy",yes(at(r,"install.restoredAfterRefusal"))?Action.RESTART_GAME:Action.RESTORE_INSTALL,true);
                case "rollback":case "unknown_key":return s("script",State.MISSING,"script_update_required",Action.UPDATE_APP,true);
                case "state_corrupt":case "state_unwritable":return s("script",State.MISSING,"script_safety_record_problem",Action.SHOW_SETUP_STEPS,true);
                case "compile_failed":case "core_failed":case "loader_error":return s("script",State.MISSING,"script_reported_start_failure",Action.SHOW_SETUP_STEPS,true);
                default:return s("script",State.UNKNOWN,"script_reported_result_unknown",Action.SHOW_SETUP_STEPS,false);
            }
        }
        if(no(at(r,"evidence.loaderReceiptSeen"))&&!liveAccepted(r)&&!String.valueOf(at(r,"evidence.bootstrapResult")).startsWith("installed"))
            return s("script",State.UNKNOWN,"script_wait_loader",Action.SHOW_SETUP_STEPS,false);
        if("GAME_CLOSED".equals(at(r,"receiver.transport"))||yes(at(r,"snapshot.data.session.exiting")))return s("script",State.UNKNOWN,"script_game_closed",Action.OPEN_GAME_NATIVE,false);
        if("STALE".equals(at(r,"receiver.transport"))||"NO_SIGNAL".equals(at(r,"receiver.transport")))return s("script",State.UNKNOWN,"script_no_fresh_signal",Action.OPEN_GAME_NATIVE,false);
        if(yes(at(r,"snapshot.synthetic"))||("LIVE".equals(at(r,"receiver.transport"))&&no(at(r,"receiver.accepted"))))return s("script",State.UNKNOWN,"script_unaccepted_source",Action.OPEN_GAME_NATIVE,false);
        if(!liveAccepted(r))return s("script",State.UNKNOWN,yes(at(r,"receiver.accepted"))&&no(at(r,"evidence.snapshotGenerationMatches"))?"script_other_generation":"script_waiting_for_game",Action.OPEN_GAME_NATIVE,false);
        if(no(at(r,"receiver.buildSupported")))return s("script",State.MISSING,"script_game_build_unsupported",Action.SHOW_SETUP_STEPS,true);
        if(!yes(at(r,"receiver.buildSupported")))return s("script",State.UNKNOWN,"script_game_build_unconfirmed",Action.SHOW_SETUP_STEPS,false);
        if(!receiptCurrent(r)||!releaseMatch(r)||!("ok".equals(at(r,"loaderStatus.result"))||"already_loaded".equals(at(r,"loaderStatus.result"))))return s("script",State.UNKNOWN,"script_install_receipt_unconfirmed",Action.RESTART_GAME,false);
        if(!Objects.equals(at(r,"expected.version"),at(r,"snapshot.data.hello.producer.version")))return s("script",State.MISSING,"script_restart_for_update",Action.RESTART_GAME,false);
        return s("script",State.OK,"script_reported_live",Action.NONE,false);
    }
    static Step data(Map<String,Object> r,Step script,Step loaders){
        boolean required=yes(at(r,"pack.requiredRebuild"));
        boolean valid=yes(at(r,"pack.validated"))&&yes(at(r,"pack.committed"))&&yes(at(r,"pack.requiredFilesPresent"));
        String format=str(at(r,"pack.atlasFormat"));boolean v1="ffxii-companion-atlas-1".equals(format),v2="ffxii-companion-atlas-2".equals(format);
        if(valid&&(v1||v2)&&yes(at(r,"pack.indexRegionsVersionMatch"))&&(v1||yes(at(r,"pack.atlasReceiptMatches")))&&yes(at(r,"pack.generationMatches"))&&!required)return s("data",State.OK,v1?"data_ready_legacy":"data_ready",Action.NONE,false);
        boolean progress="ffxii.rawcopy.v1".equals(at(r,"rawcopy.contract"))&&yes(at(r,"evidence.rawcopyCurrent"))&&Objects.equals(at(r,"expected.job"),at(r,"rawcopy.job"))&&at(r,"expected.job")!=null;
        boolean durableMissing=no(at(r,"pack.present"))||required;
        if(progress){String status=str(at(r,"rawcopy.status")),pause=str(at(r,"rawcopy.paused"));
            if("running".equals(status)&&"fight".equals(pause))return s("data",State.MISSING,"build_paused_fight",Action.WAIT_FIELD,durableMissing);
            if("running".equals(status)&&"app_away".equals(pause))return s("data",State.MISSING,"build_paused_appaway",Action.RESUME_BUILD,durableMissing);
            if("running".equals(status))return s("data",State.MISSING,"build_copying",Action.WAIT_BUILD,durableMissing);
            if("done".equals(status))return s("data",State.MISSING,"build_copied_not_committed",Action.WAIT_BUILD,durableMissing);
            if("error".equals(status))return s("data",State.MISSING,"build_copy_failed",Action.RETRY_BUILD,durableMissing);
        }
        String build=str(at(r,"pack.buildStatus"));if(yes(at(r,"evidence.appBuildCurrent"))&&("building".equals(build)||"verifying".equals(build)))return s("data",State.MISSING,"build_processing",Action.WAIT_BUILD,durableMissing);
        boolean buildable=canBuild(r,script,loaders);
        if(yes(at(r,"evidence.buildRequested"))&&(durableMissing||no(at(r,"pack.validated"))))return liveAccepted(r)?s("data",State.MISSING,"build_starting",Action.WAIT_BUILD,durableMissing):s("data",State.MISSING,"build_waiting_for_game",Action.OPEN_GAME_NATIVE,durableMissing);
        if(no(at(r,"pack.present")))return s("data",State.MISSING,"data_pack_missing",buildable?Action.START_BUILD:Action.OPEN_GAME_NATIVE,true);
        if(format!=null&&!v1&&!v2)return s("data",State.MISSING,"data_format_unsupported",Action.SHOW_SETUP_STEPS,true);
        if(no(at(r,"pack.validated"))||no(at(r,"pack.indexRegionsVersionMatch"))||(v2&&no(at(r,"pack.atlasReceiptMatches"))))return s("data",State.MISSING,"data_pack_invalid",buildable?Action.START_BUILD:Action.SHOW_SETUP_STEPS,true);
        if(valid&&!yes(at(r,"pack.generationMatches")))return s("data",State.UNKNOWN,"data_other_generation",Action.SHOW_SETUP_STEPS,false);
        if(required)return s("data",State.MISSING,"data_rebuild_required",buildable?Action.START_BUILD:Action.OPEN_GAME_NATIVE,true);
        return s("data",State.UNKNOWN,"data_not_checked",Action.SHOW_SETUP_STEPS,false);
    }
}
