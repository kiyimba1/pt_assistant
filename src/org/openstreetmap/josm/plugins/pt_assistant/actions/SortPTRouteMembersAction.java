// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.pt_assistant.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.relation.DownloadSelectedIncompleteMembersAction;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.dialogs.relation.DownloadRelationMemberTask;
import org.openstreetmap.josm.gui.dialogs.relation.GenericRelationEditor;
import org.openstreetmap.josm.gui.dialogs.relation.actions.AbstractRelationEditorAction;
import org.openstreetmap.josm.gui.dialogs.relation.actions.IRelationEditorActionAccess;
import org.openstreetmap.josm.gui.dialogs.relation.actions.IRelationEditorUpdateOn;
import org.openstreetmap.josm.gui.dialogs.relation.sort.RelationSorter;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTStop;
import org.openstreetmap.josm.plugins.pt_assistant.utils.RouteUtils;
import org.openstreetmap.josm.plugins.pt_assistant.utils.StopToWayAssigner;
import org.openstreetmap.josm.plugins.pt_assistant.utils.StopUtils;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Sorts the members of a PT route. It orders first the ways, then the stops
 * according to the assigned ways
 *
 * @author giacomo
 *
 */
public class SortPTRouteMembersAction extends AbstractRelationEditorAction {

    private static final String ACTION_NAME = "Sort PT Route Members Relation Editor";
    private GenericRelationEditor editor = null;

    /**
     * Creates a new SortPTRouteMembersAction
     */
    public SortPTRouteMembersAction(IRelationEditorActionAccess editorAccess) {
    		super(editorAccess, IRelationEditorUpdateOn.MEMBER_TABLE_SELECTION);
    		putValue(ACTION_NAME, tr(ACTION_NAME));
            new ImageProvider("icons", "sortptroutemembers").getResource().attachImageIcon(this, true);

        editor = (GenericRelationEditor) editorAccess.getEditor();
        updateEnabledState();
    }

    @Override
    public void actionPerformed(ActionEvent e) {

    		Relation rel = editor.getRelation();
    		editor.apply();

        if (rel.hasIncompleteMembers()) {
            if (JOptionPane.YES_OPTION == JOptionPane.showOptionDialog(Main.parent,
                tr("The relation has incomplete members. Do you want to download them and continue with the sorting?"),
                tr("Incomplete Members"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, null, null)) {

                List<Relation> incomplete = Collections.singletonList(rel);
                Future<?> future = MainApplication.worker.submit(new DownloadRelationMemberTask(
                        incomplete,
                        Utils.filteredCollection(
                                DownloadSelectedIncompleteMembersAction.buildSetOfIncompleteMembers(
                                        Collections.singletonList(rel)), OsmPrimitive.class),
                        MainApplication.getLayerManager().getEditLayer()));

                MainApplication.worker.submit(() -> {
                		try {
                            future.get();
                            continueAfterDownload(rel);
                        } catch (InterruptedException | ExecutionException e1) {
                            Logging.error(e1);
                            return;
                        }
                 });
            } else
                return;
        } else
            continueAfterDownload(rel);

    }

    private void continueAfterDownload(Relation rel) {
        Relation newRel = new Relation(rel);
        sortPTRouteMembers(newRel);
        UndoRedoHandler.getInstance().add(new ChangeCommand(rel, newRel));
        editor.reloadDataFromRelation();
    }

    /***
     * Sort the members of the PT route.
     *
     * @param rel route to be sorted
     */
    public static void sortPTRouteMembers(Relation rel) {
        if (!RouteUtils.isVersionTwoPTRoute(rel)) {
            return;
        }

        if (rel.hasTag("fixme:relation", "order members")) {
            rel.remove("fixme:relation");
        }

        //first loop trough all the members and remove the roles
        List<RelationMember> members = new ArrayList<>();
        List<RelationMember> oldMembers = rel.getMembers();
        for (int i = 0; i < oldMembers.size(); i++) {
            RelationMember rm = oldMembers.get(i);
            if (!PTStop.isPTPlatform(rm) && !PTStop.isPTStopPosition(rm))
                members.add(new RelationMember("", rm.getMember()));
            else
                members.add(rm);
            rel.removeMember(0);
        }

        //sort the members with the built in sorting function in order to get
        //the continuity of the ways
        members = new RelationSorter().sortMembers(members);

        //divide stops and ways
        List<RelationMember> stops = new ArrayList<>();
        List<RelationMember> wayMembers = new ArrayList<>();
        List<Way> ways = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            RelationMember rm = members.get(i);
            if (PTStop.isPTPlatform(rm) || PTStop.isPTStopPosition(rm))
                stops.add(rm);
            else {
                wayMembers.add(rm);
                if (rm.getType() == OsmPrimitiveType.WAY)
                    ways.add(rm.getWay());
            }
        }

        //couple together stop positions and platforms that are part of the same
        //stop. the only way used to determine whether they are part of the same
        //stop or not is the name. this should be improved by using also the
        //distance
        Map<String, List<PTStop>> stopsByName = new HashMap<>();
        List<PTStop> unnamed = new ArrayList<>();
        stops.forEach(rm -> {
            String name = getStopName(rm.getMember());
            if (name != null) {
                if (!stopsByName.containsKey(name))
                    stopsByName.put(name, new ArrayList<>());
                List<PTStop> ls = stopsByName.get(name);
                if (ls.isEmpty()) {
                    ls.add(new PTStop(rm));
                } else {
                    PTStop stp = ls.get(ls.size() - 1);
                    if (!stp.addStopElement(rm)) {
                        ls.add(new PTStop(rm));
                    }
                }
            } else {
                unnamed.add(new PTStop(rm));
            }
        });

        //assign to each stop the corresponding way. if none is found add already
        //the stop to the relation since it is not possible to reason on the order
        StopToWayAssigner assigner = new StopToWayAssigner(ways);
        List<PTStop> ptstops = new ArrayList<>();
        removeWrongSideStops(ptstops, wayMembers);
        stopsByName.values().forEach(ptstops::addAll);

        Map<Way, List<PTStop>> wayStop = new HashMap<>();
        ptstops.forEach(stop -> {
            Way way = assigner.get(stop);
            if (way == null) {
                addStopToRelation(rel, stop);
            }
            if (!wayStop.containsKey(way))
                wayStop.put(way, new ArrayList<PTStop>());
            wayStop.get(way).add(stop);
        });

        unnamed.forEach(stop -> {
            Way way = assigner.get(stop);
            if (way == null) {
                addStopToRelation(rel, stop);
            }
            if (!wayStop.containsKey(way))
                wayStop.put(way, new ArrayList<PTStop>());
            wayStop.get(way).add(stop);
        });

        //based on the order of the ways, add the stops to the relation
        for (int i = 0; i < wayMembers.size(); i++) {
            RelationMember wm = wayMembers.get(i);
            Way prev = null;
            Way next = null;
            if (i > 0) {
                RelationMember wmp = wayMembers.get(i-1);
                if (wmp.getType() == OsmPrimitiveType.WAY)
                    prev = wmp.getWay();
            }
            if (i < wayMembers.size() - 1) {
                RelationMember wmn = wayMembers.get(i+1);
                if (wmn.getType() == OsmPrimitiveType.WAY)
                    next = wmn.getWay();
            }

            if (wm.getType() == OsmPrimitiveType.WAY) {
                Way curr = wm.getWay();
                List<PTStop> stps = wayStop.get(curr);
                if (stps != null) {
                    //if for one way there are more than one stop assigned to it,
                    //another sorting step is needed
                    if (stps.size() > 1)
                        stps = sortSameWayStops(stps, curr, prev, next);
                    stps.forEach(stop -> {
                        if (stop != null) {
                            addStopToRelation(rel, stop);
                        }
                    });
                }
            }
        }

        wayMembers.forEach(rel::addMember);
    }

    private static void addStopToRelation(Relation rel, PTStop stop) {
        if (stop.getStopPositionRM() != null)
            rel.addMember(stop.getStopPositionRM());
        if (stop.getPlatformRM() != null)
            rel.addMember(stop.getPlatformRM());
    }

    //sorts the stops that are assigned to the same way. this is done based on
    //the distance from the previous way.
    private static List<PTStop> sortSameWayStops(List<PTStop> stps, Way way, Way prev, Way next) {
        Map<Node, List<PTStop>> closeNodes = new HashMap<>();
        List<PTStop> noLocationStops = new ArrayList<>();
        List<Node> nodes = way.getNodes();
        for (PTStop stop : stps) {
            Node closest = findClosestNode(stop, nodes);
            if (closest == null) {
                noLocationStops.add(stop);
                continue;
            }
            if (!closeNodes.containsKey(closest)) {
                closeNodes.put(closest, new ArrayList<>());
            }
            closeNodes.get(closest).add(stop);
        }

        boolean reverse = false;

        if (prev != null) {
            reverse = prev.firstNode().equals(way.lastNode())
                        || prev.lastNode().equals(way.lastNode());
        } else if (next != null) {
            reverse = next.firstNode().equals(way.firstNode())
                    || next.lastNode().equals(way.firstNode());
        }

        if (reverse)
            Collections.reverse(nodes);

        List<PTStop> ret = getSortedStops(nodes, closeNodes);
        ret.addAll(noLocationStops);
        return ret;
    }

    private static List<PTStop> getSortedStops(List<Node> nodes,
            Map<Node, List<PTStop>> closeNodes) {

        List<PTStop> ret = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            Node prevNode = i > 0 ? nodes.get(i - 1) : n;
            List<PTStop> stops = closeNodes.get(n);
            if (stops != null) {
                if (stops.size() > 1) {
                    stops.sort((s1, s2) -> {
                        Double d1 = stopEastNorth(s1).distance(prevNode.getEastNorth());
                        Double d2 = stopEastNorth(s2).distance(prevNode.getEastNorth());
                        return d1.compareTo(d2);
                    });
                }
                stops.forEach(ret::add);
            }
        }

        return ret;
    }

    private static Node findClosestNode(PTStop stop, List<Node> nodes) {
        EastNorth stopEN = stopEastNorth(stop);
        if (stopEN == null)
            return null;
        double minDist = Double.MAX_VALUE;
        Node closest = null;
        for (Node node : nodes) {
            double dist = node.getEastNorth().distance(stopEN);
            if (dist < minDist) {
                minDist = dist;
                closest = node;
            }
        }
        return closest;
    }

    private static EastNorth stopEastNorth(PTStop stop) {
        if (stop.getStopPosition() != null)
            return stop.getStopPosition().getEastNorth();
        OsmPrimitive prim = stop.getPlatform();
        if (prim.getType() == OsmPrimitiveType.WAY)
            return ((Way) prim).firstNode().getEastNorth();
        else if (prim.getType() == OsmPrimitiveType.NODE)
            return ((Node) prim).getEastNorth();
        else
            return null;
    }

    private static String getStopName(OsmPrimitive p) {
        for (Relation ref : Utils.filteredCollection(p.getReferrers(), Relation.class)) {
            if (ref.hasTag("type", "public_transport")
                    && StopUtils.isStopArea(ref)
                    && ref.getName() != null) {
                return ref.getName();
            }
        }
        return p.getName();
    }

    private static void removeWrongSideStops(List<PTStop> ptstop, List<RelationMember> wayMembers) {
    		for (int i=0; i<wayMembers.size(); i++) {
    			RelationMember wm = wayMembers.get(i);
    	        Way prev = null;
    	        Way next = null;
    	        if (i > 0) {
    	            RelationMember wmp = wayMembers.get(i-1);
    	            if (wmp.getType() == OsmPrimitiveType.WAY)
    	                prev = wmp.getWay();
    	        }
    	        if (i < wayMembers.size() - 1) {
    	            RelationMember wmn = wayMembers.get(i+1);
    	            if (wmn.getType() == OsmPrimitiveType.WAY)
    	                next = wmn.getWay();
    	        }
    	        if (wm.getType() == OsmPrimitiveType.WAY) {
    	            Way curr = wm.getWay();
    	            Node firstNode = findCommonNode(curr, prev);
    	            Node lastNode = findCommonNode(curr, next);
    	            System.out.println(i);
    	            	if (firstNode != null && firstNode.equals(curr.getNode(0))) {
    	            		System.out.println("Front Way 1 == " + curr.getName());
    	            	} else if (firstNode != null && firstNode.equals(curr.getNode(curr.getNodesCount() - 1))) {
    	            		System.out.println("Back Way 2 == " + curr.getName());
    	            	} else if (lastNode != null && lastNode.equals(curr.getNode(0))) {
    	            		System.out.println("Back Way 3 == " + curr.getName());
    	            	} else if (lastNode != null && lastNode.equals(curr.getNode(curr.getNodesCount() - 1))) {
    	            		System.out.println("Front Way 4 == " + curr.getName());
    	            	}
    	        }
    		}
    }

    private static Node findCommonNode(Way w1, Way w2) {
    		if (w1 == null || w2 == null)
    			return null;
    		for (int i = 0; i< w1.getNodes().size(); i++) {
    			for (int j = 0; j<w2.getNodes().size(); j++) {
    				if (w1.getNodes().get(i).equals(w2.getNodes().get(j)))
    					return w1.getNodes().get(i);
    			}
    		}
    		return null;
    }

	@Override
	protected void updateEnabledState() {
		if (editor != null && !RouteUtils.isPTRoute(editor.getRelation())) {
            setEnabled(false);
            return;
        }
		setEnabled(true);
	}
}
