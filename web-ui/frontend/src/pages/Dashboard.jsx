import React, { useState, useEffect } from 'react';
import { Plus, CaretRight } from '@phosphor-icons/react';
import { useNavigate } from 'react-router-dom';
import ChatWidget from '../components/ChatWidget';
import NewCaseModal from '../components/NewCaseModal';
import { caseService } from '../services/caseService';
import '../styles/dashboard.css';

export default function Dashboard() {
    const [cases, setCases] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const navigate = useNavigate();

    const existingPatients = React.useMemo(() => {
        const unique = new Map();
        cases.forEach((c) => {
            const key = `${(c.fname || '').trim().toLowerCase()}|${(c.lname || '').trim().toLowerCase()}`;
            if (!unique.has(key)) {
                unique.set(key, {
                    key,
                    fname: c.fname,
                    lname: c.lname,
                    patient_age: c.patient_age,
                });
            }
        });
        return Array.from(unique.values());
    }, [cases]);

    const loadCases = async () => {
        const data = await caseService.getCases();
        if (data) setCases(data);
    };

    useEffect(() => {
        loadCases();
    }, []);

    const handleCaseCreated = async (newCase) => {
        await loadCases();
        if (newCase?.id) {
            navigate(`/cases/${encodeURIComponent(newCase.id)}`);
        }
    };

    return (
        <>
            <div className="page-header">
                <h1>Dashboard</h1>
            </div>

            <div className="toolbar">
                <div className="search-container">
                    <input type="text" placeholder="Search patients or case IDs..." />
                </div>
                <button className="new-case-btn" onClick={() => setIsModalOpen(true)}>
                    <Plus weight="bold" />
                    New Case
                </button>
            </div>

            <div className="table-card">
                <table>
                    <thead className="table-header">
                        <tr>
                            <th style={{ width: '25%' }}>Patient Name</th>
                            <th style={{ width: '15%' }}>Case ID</th>
                            <th style={{ width: '15%' }}>Date</th>
                            <th style={{ width: '20%' }}>Type</th>
                            <th style={{ width: '15%' }}>Status</th>
                            <th style={{ width: '10%' }}></th>
                        </tr>
                    </thead>
                    <tbody>
                        {cases.length > 0 ? (
                            cases.map((c) => {
                                // Compute initials safely
                                const initials = ((c.fname && c.fname[0]) || '') + ((c.lname && c.lname[0]) || '');
                                // Format date safely
                                const dateStr = c.created_at ? new Date(c.created_at).toLocaleDateString() : 'N/A';
                                // Determine display ID and fallback (using case_id string for display, id for key)
                                const displayId = c.case_id || String(c.id);
                                const safeDisplayId = String(displayId).replace(/-/g, '-<br>');

                                return (
                                    <tr className="table-row" key={c.id}>
                                        <td>
                                            <div className="patient-cell">
                                                <div className="patient-avatar avatar-blue">
                                                    {initials.toUpperCase()}
                                                </div>
                                                <span className="patient-name">{c.fname} {c.lname}</span>
                                            </div>
                                        </td>
                                        <td>
                                            <div className="case-id-cell" dangerouslySetInnerHTML={{ __html: safeDisplayId }}></div>
                                        </td>
                                        <td className="date-cell">{dateStr}</td>
                                        <td className="type-cell">{c.case_type || 'CBCT'}</td>
                                        <td>
                                            <span className={`status-badge ${c.status === 'Ready' ? 'status-ready' : 'status-processing'}`}>
                                                {c.status || 'Processing'}
                                            </span>
                                        </td>
                                        <td className="action-cell">
                                            <CaretRight
                                                onClick={() => navigate(`/cases/${encodeURIComponent(c.id)}`)}
                                                style={{ cursor: 'pointer', padding: '0px' }}
                                            />
                                        </td>
                                    </tr>
                                );
                            })
                        ) : (
                            <tr>
                                <td colSpan="6" style={{ textAlign: 'center', padding: '2rem' }}>No cases found. Create a new case to get started.</td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>

            <ChatWidget />

            <NewCaseModal
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                onCaseCreated={handleCaseCreated}
                existingPatients={existingPatients}
            />
        </>
    );
}
