/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2011 OpenConcerto, by ILM Informatique. All rights reserved.
 * 
 * The contents of this file are subject to the terms of the GNU General Public License Version 3
 * only ("GPL"). You may not use this file except in compliance with the License. You can obtain a
 * copy of the License at http://www.gnu.org/licenses/gpl-3.0.html See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each file.
 */
 
 package org.openconcerto.sql.request;

import org.openconcerto.sql.Log;
import org.openconcerto.sql.element.SQLComponent;
import org.openconcerto.sql.model.SQLField;
import org.openconcerto.sql.model.SQLRow;
import org.openconcerto.sql.model.SQLRowAccessor;
import org.openconcerto.sql.model.SQLRowValues;
import org.openconcerto.sql.model.SQLTable;
import org.openconcerto.sql.model.SQLTableEvent;
import org.openconcerto.sql.model.SQLTableEvent.Mode;
import org.openconcerto.sql.model.SQLTableModifiedListener;
import org.openconcerto.ui.SwingThreadUtils;

import java.awt.Component;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.SwingUtilities;

/**
 * A view of a row as a collection of SQLRowItemView. NOTE: all methods of this class are expected
 * to be called from within the EDT (unless otherwise stated).
 * 
 * 
 * @author Sylvain CUAZ
 * @see #add(SQLRowItemView)
 * @see #select(SQLRowAccessor)
 * @see #insert()
 * @see #update()
 */
public class SQLRowView extends BaseSQLRequest {

    // la table cible de cette requete
    private final SQLTable table;
    private final SQLTableModifiedListener tableListener;
    // l'id affiché ou SQLRow.NONEXISTANT_ID si les valeurs affichées ne sont pas liées à une ligne
    // dans la base
    private int selectedID;
    // les valeurs affichées
    private final Map<String, SQLRowItemView> views;
    private final List<SQLRowItemView> viewsOrdered;

    private boolean readOnly;
    private boolean filling;
    private boolean updating;

    private final PropertyChangeSupport supp;

    // ne peut contenir des champs que de cette table
    public SQLRowView(SQLTable t) {
        if (t == null)
            throw new NullPointerException("null SQLTable");
        else if (!t.isRowable())
            throw new IllegalArgumentException(t + " is not rowable");

        this.supp = new PropertyChangeSupport(this);
        this.table = t;
        this.views = new HashMap<String, SQLRowItemView>();
        this.viewsOrdered = new LinkedList<SQLRowItemView>();
        this.readOnly = false;
        this.filling = false;
        this.updating = false;
        this.selectedID = SQLRow.NONEXISTANT_ID;
        this.tableListener = new SQLTableModifiedListener() {
            @Override
            public void tableModified(SQLTableEvent evt) {
                if (evt.getMode() == Mode.ROW_UPDATED)
                    this.rowModified(evt.getTable(), evt.getId());
                else if (evt.getMode() == Mode.ROW_DELETED)
                    this.rowDeleted(evt.getTable(), evt.getId());
                // else don't care
            }

            public void rowModified(SQLTable t, int id) {
                if (!isUpdating() && existsInDB()) {
                    if (!t.equals(getTable())) {
                        // TODO trouver si ca nous concerne => select
                        Thread.dumpStack();
                    } else if (getSelectedID() == id) {
                        // rafraichir
                        select(id);
                    }
                }
            }

            public void rowDeleted(SQLTable t, int id) {
                if (!isUpdating() && existsInDB()) {
                    if (!t.equals(getTable())) {
                        // TODO trouver si ca nous concerne
                        Thread.dumpStack();
                    } else if (getSelectedID() == id) {
                        SwingThreadUtils.invoke(new Runnable() {
                            public void run() {
                                select(null);
                            }
                        });
                    }
                }
            }
        };
    }

    private synchronized void setReadOnlySelection(final boolean b) {
        if (this.readOnly != b) {
            this.readOnly = b;
            this.supp.firePropertyChange(SQLComponent.READ_ONLY_PROP, !b, b);
        }
    }

    // false if no ID
    public synchronized boolean isReadOnlySelection() {
        return this.readOnly;
    }

    private final void checkRO() throws SQLException {
        if (this.isReadOnlySelection())
            throw new SQLException("Read only selection");
    }

    public synchronized boolean isUpdating() {
        return this.updating;
    }

    public final void activate(boolean b) {
        if (b) {
            this.table.addTableModifiedListener(this.tableListener);
            if (this.existsInDB())
                // to catch up to the changes which happened while we weren't listening
                this.select(this.getSelectedID());
        } else
            this.table.removeTableModifiedListener(this.tableListener);
    }

    /**
     * Add a view.
     * 
     * @param obj object to add.
     */
    public void add(SQLRowItemView obj) {
        if (obj.getSQLName() == null)
            throw new IllegalArgumentException("null SQL name for " + obj);
        if (this.views.containsKey(obj.getSQLName()))
            throw new IllegalStateException("2 views named " + obj.getSQLName() + ": " + this.views.get(obj.getSQLName()) + " " + obj);
        this.views.put(obj.getSQLName(), obj);
        this.viewsOrdered.add(obj);
        assert this.viewsOrdered.size() == this.views.size();
    }

    /**
     * Set the order of the views. Useful when some views depend on others.
     * 
     * @param names names of the {@link SQLRowItemView}.
     */
    public final void setViewsOrder(final Collection<String> names) {
        final LinkedHashSet<String> nameSet = new LinkedHashSet<String>(names);
        if (!this.views.keySet().equals(nameSet))
            throw new IllegalArgumentException("Names mismatch " + this.views.keySet() + " != " + nameSet);

        final LinkedList<SQLRowItemView> l = new LinkedList<SQLRowItemView>();
        for (final String n : nameSet) {
            l.add(this.views.get(n));
        }
        this.viewsOrdered.clear();
        this.viewsOrdered.addAll(l);
        assert this.viewsOrdered.size() == this.views.size();
    }

    /**
     * Display the passed id. As an exception, this can be called from outside the EDT
     * 
     * @param id id of the row to display.
     */
    public void select(int id) {
        final SQLRow row = this.getTable().getRow(id);
        SwingThreadUtils.invoke(new Runnable() {
            public void run() {
                select(row);
            }
        });
    }

    public void select(SQLRowAccessor r) {
        this.select(r, null);
    }

    /**
     * Fill the item views with values from <code>r</code>. If r is <code>null</code> this will be
     * reset. If r has no ID, the selectedID doesn't change.
     * 
     * @param r the row to display, can be <code>null</code>.
     * @param views the subset of views to fill, <code>null</code> meaning all.
     */
    public void select(final SQLRowAccessor r, final Set<String> views) {
        this.setFilling(true);
        try {
            if (r == null) {
                this.setSelectedID(SQLRow.NONEXISTANT_ID);
                for (final SQLRowItemView view : this.getViewsFast()) {
                    if (views == null || views.contains(view.getSQLName()))
                        view.resetValue();
                }
            } else {
                if (!this.getTable().equals(r.getTable()))
                    throw new IllegalArgumentException("r is not of table " + this.getTable() + " : " + r);
                // set selectedID before show() since some views might need it (eg for validation)
                if (r.getID() != SQLRow.NONEXISTANT_ID)
                    this.setSelectedID(r.getID());
                for (final SQLRowItemView view : this.getViewsFast()) {
                    if (views == null || views.contains(view.getSQLName()))
                        view.show(r);
                }
            }

            if (this.getTable().contains(SQLComponent.READ_ONLY_FIELD)) {
                final Boolean readOnlySel;
                if (r == null) {
                    readOnlySel = false;
                } else if (r.getIDNumber() == null) {
                    // don't change the row => same value
                    readOnlySel = null;
                } else {
                    readOnlySel = SQLComponent.isReadOnly(r);
                }
                if (readOnlySel != null) {
                    this.setReadOnlySelection(readOnlySel);
                }
            }
        } finally {
            this.setFilling(false);
        }
    }

    public final void detach() {
        this.setSelectedID(SQLRow.NONEXISTANT_ID);
        this.setReadOnlySelection(false);
    }

    /**
     * Mets à jour la ligne sélectionnée avec les nouvelles valeurs des SQLObjects.
     * 
     * @throws SQLException if the update couldn't complete.
     */
    public void update() throws SQLException {
        checkRO();
        // this ship is sailed, don't accept updates from the db anymore
        // this allows to archive a private (thus changing our fk) without
        // overwriting our values
        synchronized (this) {
            this.updating = true;
        }
        this.update(this.getSelectedID());
        synchronized (this) {
            this.updating = false;
        }
    }

    /**
     * Permet de mettre à jour une ligne existante avec les valeurs courantes.
     * 
     * @param id l'id à mettre à jour.
     * @throws SQLException if the update couldn't complete.
     */
    private void update(int id) throws SQLException {
        if (id == this.getTable().getUndefinedID())
            throw new IllegalArgumentException("can't update undefined");
        if (id == SQLRow.NONEXISTANT_ID)
            throw new IllegalArgumentException("NONEXISTANT_ID");
        Log.get().fine("updating " + this.getTable() + " " + id);

        final SQLRowValues vals = new SQLRowValues(this.getTable());
        for (final SQLRowItemView view : this.getViewsFast()) {
            view.update(vals);
        }
        vals.update(id);
    }

    public SQLRow insert() throws SQLException {
        return fillVals().insert();
    }

    private SQLRowValues fillVals() throws SQLException {
        checkRO();
        final SQLRowValues vals = new SQLRowValues(this.getTable());
        for (final SQLRowItemView view : this.getViewsFast()) {
            view.insert(vals);
        }
        return vals;
    }

    public SQLRow insert(SQLRow order) throws SQLException {
        final SQLRowValues vals = fillVals();
        vals.setOrder(order, true);
        return vals.insertVerbatim();
    }

    public SQLTable getTable() {
        return this.table;
    }

    public Set<SQLRowItemView> getViews() {
        return new LinkedHashSet<SQLRowItemView>(this.getViewsFast());
    }

    public Map<String, SQLRowItemView> getViewsMap() {
        return Collections.unmodifiableMap(this.views);
    }

    private final Collection<SQLRowItemView> getViewsFast() {
        return this.viewsOrdered;
    }

    @Override
    public String toString() {
        return this.getClass() + " with " + this.getViewsFast();
    }

    /**
     * Returns the corresponding view.
     * 
     * @param name name of the desired view.
     * @return the corresponding view, or <code>null</code> if none exists.
     */
    public SQLRowItemView getView(String name) {
        return this.views.get(name);
    }

    /**
     * Returns the view whose component is a parent of the passed one.
     * 
     * @param comp a Component used by a view (or a descendant of one).
     * @return the corresponding view, or <code>null</code> if none exists.
     */
    public SQLRowItemView getView(Component comp) {
        for (final SQLRowItemView view : this.getViewsFast()) {
            if (SwingUtilities.isDescendingFrom(comp, view.getComp()))
                return view;
        }
        return null;
    }

    public void resetValue() {
        this.select(null);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.openconcerto.devis.request.BaseSQLRequest#getAllFields()
     */
    public Collection<SQLField> getAllFields() {
        final Set<SQLField> res = new HashSet<SQLField>();
        for (final SQLRowItemView view : this.getViewsFast()) {
            res.addAll(view.getFields());
        }
        return res;
    }

    private void setSelectedID(int selectedID) {
        this.selectedID = selectedID;
        this.supp.firePropertyChange("selectedID", null, this.selectedID);
    }

    public final int getSelectedID() {
        return this.selectedID;
    }

    /**
     * If this request represents an actual row in the database.
     * 
     * @return <code>true</code> if this request is linked with the base.
     */
    public final boolean existsInDB() {
        return this.getSelectedID() != SQLRow.NONEXISTANT_ID;
    }

    /**
     * Is this view filing in its items.
     * 
     * @return <code>true</code> if items values are being set.
     */
    public final boolean isFilling() {
        return this.filling;
    }

    private final void setFilling(boolean filling) {
        this.filling = filling;
        this.supp.firePropertyChange("filling", null, this.filling);
    }

    public final void addListener(PropertyChangeListener l) {
        this.supp.addPropertyChangeListener(l);
    }

    public final void rmListener(PropertyChangeListener l) {
        this.supp.removePropertyChangeListener(l);
    }

    public final void addListener(PropertyChangeListener l, final String name) {
        this.supp.addPropertyChangeListener(name, l);
    }

    public final void rmListener(PropertyChangeListener l, final String name) {
        this.supp.removePropertyChangeListener(name, l);
    }
}
