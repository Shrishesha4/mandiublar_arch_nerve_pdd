import React from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
    SquaresFour,
    Heartbeat,
    FileText,
    Gear,
    CaretRight,
    SignOut,
    X
} from '@phosphor-icons/react';
import { authService } from '../services/authService';
import { authStore } from '../services/authStore';

export default function Sidebar({ isOpen, onClose }) {
    const location = useLocation();

    return (
        <aside className={`sidebar ${isOpen ? 'open' : ''}`}>
            <div className="logo-area">
                <div className="logo-box">
                    <img src="/favicon.svg" alt="Logo" style={{ width: '24px', height: '24px' }} />
                </div>
                <span className="logo-text">ImplantAI</span>
                {/* Mobile close button should be handled via props or CSS logic if needed, but standard sidebar doesn't usually have it inside explicitly unless mobile */}
                <button className="mobile-close-btn" style={{ marginLeft: 'auto', display: 'none' }} onClick={onClose}>
                    <X size={24} />
                </button>
            </div>

            <nav className="nav-menu">
                <NavLink to="/dashboard" className="nav-item" end onClick={onClose}>
                    <SquaresFour size={20} />
                    <span>Dashboard</span>
                </NavLink>
                <NavLink to="/analysis" className="nav-item" onClick={onClose}>
                    <Heartbeat size={20} />
                    <span>Analysis</span>
                </NavLink>
                <NavLink to="/reports" className="nav-item" onClick={onClose}>
                    <FileText size={20} />
                    <span>Reports</span>
                </NavLink>
                <NavLink to="/settings" className="nav-item" onClick={onClose}>
                    <Gear size={20} />
                    <span>Settings</span>
                </NavLink>
            </nav>

            <div>
                <div className="user-profile" onClick={() => window.location.href = '/settings/profile'}>
                    <div className="avatar-circle">SW</div>
                    <div className="profile-info">
                        <h4 id="sidebar-name">{authStore.getUserName() || 'Dr. Sarah Wilson'}</h4>
                        <p>Oral Surgeon</p>
                    </div>
                    <CaretRight size={16} style={{ marginLeft: 'auto', color: '#94A3B8' }} />
                </div>
                <div className="sign-out" onClick={() => {
                    authService.logout();
                    window.location.href = '/';
                }} style={{ cursor: 'pointer' }}>
                    <SignOut size={20} />
                    Sign Out
                </div>
            </div>
        </aside>
    );
}
